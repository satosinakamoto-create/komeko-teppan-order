package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.domain.RecipeLine;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.repository.RecipeLineRepository;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.service.ImageStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品の削除とレシピの後始末（2026-09-07 の全体点検 #1）。
 *
 * <p><b>何が壊れていたか</b><br>
 * {@code recipe_line.menu_item_id} は NOT NULL の外部キー（V4）なのに、
 * 商品の削除はレシピ行を消していなかった。レシピ付きの商品を削除すると
 * コミット時に FK 違反で 500。さらに削除の順序が
 * 「DB の delete（コミット時に失敗）→ 画像ファイルの削除（即時・取り消し不能）」
 * だったので、失敗すると<b>商品は残ったまま画像だけ消えた</b>。
 *
 * <p><b>直したあとの約束</b><br>
 * レシピは原価計算のための付属データなので、商品と一緒に消える
 * （カテゴリや卓のような「止める門」にはしない）。ただし黙って消さず、
 * 消した行数をフラッシュメッセージで知らせる。
 * 画像ファイルは DB の削除が確定したあとにだけ消す。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品の削除とレシピの後始末")
class AdminMenuItemDeleteTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private ImageStorageService imageStorageService;

    /**
     * 本物をそのまま使い、失敗のテストのときだけ例外を差し込む。
     *
     * <p>修正後は本物の DB でこの失敗を起こせなくなった（それが修正の成果）ので、
     * 「DB が失敗したら画像は無傷」という順序の約束は、失敗だけを
     * 差し替えて確かめる。
     */
    @SpyBean
    private RecipeLineRepository recipeLineRepository;

    private MenuItem itemWithRecipe(String marker) {
        Category category = categoryRepository.save(
                new Category("削除テスト" + marker + System.nanoTime(), 999));
        MenuItem item = menuItemRepository.save(
                new MenuItem(category, "削除テスト品" + marker + System.nanoTime(), 800));
        Ingredient ingredient = ingredientRepository.save(
                new Ingredient("削除テスト食材" + marker + System.nanoTime(), IngredientUnit.GRAM));
        recipeLineRepository.save(new RecipeLine(item, ingredient, new BigDecimal("120")));
        return item;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ レシピ付きの商品を削除できる（レシピも一緒に消え、行数を知らせる）")
    void deletesItemTogetherWithItsRecipe() throws Exception {
        MenuItem item = itemWithRecipe("A");

        mockMvc.perform(post("/admin/items/" + item.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 黙って消さない。レシピを付けた本人が「消えた」と気づけるように
                .andExpect(flash().attribute("flashSuccess",
                        org.hamcrest.Matchers.containsString("レシピも一緒に削除しました（1 行）")));

        assertThat(menuItemRepository.findById(item.getId())).isEmpty();
        assertThat(recipeLineRepository.findByMenuItem(item.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("レシピの無い商品の削除は、レシピの文言を出さない")
    void plainDeleteHasNoRecipeMention() throws Exception {
        Category category = categoryRepository.save(
                new Category("削除テストB" + System.nanoTime(), 999));
        MenuItem item = menuItemRepository.save(
                new MenuItem(category, "削除テスト品B" + System.nanoTime(), 800));

        mockMvc.perform(post("/admin/items/" + item.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 消していないものを「消しました」と言うと、次から誰も文言を信じなくなる
                .andExpect(flash().attribute("flashSuccess",
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("レシピ"))));

        assertThat(menuItemRepository.findById(item.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ DB の削除が失敗したら、画像ファイルは無傷で残る")
    void imageSurvivesWhenTheDatabaseFails() throws Exception {
        MenuItem item = itemWithRecipe("C");
        // 本物の画像ファイルを置く。パスの形は ImageStorageService の公開形式
        String fileName = "delete-test-" + System.nanoTime() + ".png";
        Path file = imageStorageService.getUploadDir().resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        item.setImagePath(ImageStorageService.PUBLIC_PREFIX + fileName);
        menuItemRepository.save(item);

        try {
            doThrow(new DataIntegrityViolationException("テスト用の失敗"))
                    .when(recipeLineRepository).deleteByMenuItemId(any());

            assertThatThrownBy(() ->
                    mockMvc.perform(post("/admin/items/" + item.getId() + "/delete").with(csrf())))
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);

            // ★ ここが本題。商品が残っているのに写真だけ消えていた事故を塞ぐ。
            //   画像の削除はロールバックできないので、DB が確定するまで触らない
            assertThat(menuItemRepository.findById(item.getId()))
                    .as("失敗したのに商品が消えている").isPresent();
            assertThat(Files.exists(file))
                    .as("DB が失敗したのに画像だけ消えた").isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
