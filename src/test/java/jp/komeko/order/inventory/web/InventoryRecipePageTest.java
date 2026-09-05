package jp.komeko.order.inventory.web;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * レシピ・原価表の画面を、実際に<b>描画まで</b>確認するテスト。
 *
 * <p>この画面は {@code RecipeCost}（record）とその中の {@code LineCost}（入れ子の record）を
 * 読みます。Thymeleaf は record のアクセサを {@code ()} 付きで呼ぶ必要があり、
 * <b>書き間違えてもコンパイルは通ります</b>。描かせるまで気づけません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("レシピ・原価表の画面")
class InventoryRecipePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private InventoryTestFixture fixture;

    private Ingredient ingredient;
    private MenuItem menuItem;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        ingredient = ingredients.save(new Ingredient("画面テスト用米粉-" + suffix, IngredientUnit.GRAM));
        menuItem = fixture.createMenuItem("画面テスト用鉄板焼き-" + suffix, 800);
    }

    @Test
    @DisplayName("ログインしていなければ入れない")
    void requires_login() throws Exception {
        mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("原価表が描ける（レシピ未登録の商品も行として出る）")
    void renders_cost_table() throws Exception {
        // 一覧から消してしまうと、登録し忘れていることに気づけない
        mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/recipes"))
                .andExpect(content().string(containsString(menuItem.getName())))
                .andExpect(content().string(containsString("未登録")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("編集画面が描ける")
    void renders_edit() throws Exception {
        mockMvc.perform(get("/inventory/recipes/" + menuItem.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/recipe-edit"))
                .andExpect(content().string(containsString(menuItem.getName())));
    }

    @Test
    @WithMockUser(roles = "ADMIN")   // 原価の表示は ADMIN（と公開デモの GUEST）だけの仕様
    @DisplayName("材料を足せて、原価がその場に出る")
    void can_add_line_and_see_cost() throws Exception {
        // 100g を 50 円（税込8%）で仕入れた → 0.5 円/g
        fixture.recordPurchase(ingredient, 50, new BigDecimal("100"), 8);

        mockMvc.perform(post("/inventory/recipes/" + menuItem.getId() + "/lines").with(csrf())
                        .param("ingredientId", String.valueOf(ingredient.getId()))
                        .param("qtyPerItem", "60"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/recipes/" + menuItem.getId()));

        assertThat(recipeService.linesOf(menuItem.getId())).hasSize(1);

        // 60g × 0.5 円 = 30 円。売価 800 円なら原価率 3.8%
        mockMvc.perform(get("/inventory/recipes/" + menuItem.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("¥30")))
                .andExpect(content().string(containsString("3.8%")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("量が空欄なら足せない（描き直してエラーを出す）")
    void rejects_missing_quantity() throws Exception {
        mockMvc.perform(post("/inventory/recipes/" + menuItem.getId() + "/lines").with(csrf())
                        .param("ingredientId", String.valueOf(ingredient.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/recipe-edit"))
                .andExpect(content().string(containsString("1品あたりの量を入力してください")));

        assertThat(recipeService.linesOf(menuItem.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("材料を外せる")
    void can_remove_line() throws Exception {
        recipeService.addLine(menuItem.getId(), ingredient.getId(), new BigDecimal("60"), null);
        Long lineId = recipeService.linesOf(menuItem.getId()).get(0).getId();

        mockMvc.perform(post("/inventory/recipes/" + menuItem.getId()
                        + "/lines/" + lineId + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(recipeService.linesOf(menuItem.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")   // 単価不明の警告も原価の一部なので ADMIN 表示
    @DisplayName("一度も仕入れていない食材は「単価不明」と断る")
    void warns_about_unknown_unit_cost() throws Exception {
        // 0 円として足すと原価率が実際より低く出て「思ったより儲かる」と誤解させる
        recipeService.addLine(menuItem.getId(), ingredient.getId(), new BigDecimal("60"), null);

        mockMvc.perform(get("/inventory/recipes/" + menuItem.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("単価が分かりません")));
    }
}
