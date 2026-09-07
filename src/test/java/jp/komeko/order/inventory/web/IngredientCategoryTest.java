package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientCategory;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 食材の分類と「カテゴリーから検索」（設計 現04 441:2715 / 2026-09-07）。
 *
 * <p><b>なぜ分類を足したか</b><br>
 * 食材が 30 も 50 も並ぶと、名前を思い出せないものは探せません。
 * 「たしか魚のなにか」までは覚えているので、そこから絞れるようにします。
 *
 * <p><b>いちばん気をつけているのは「未分類」の扱い</b>です。
 * 既存の食材はどれも分類されていないので、既定を OTHER にすると
 * 「分類し忘れ」と「本当にその他」が同じ棚に混ざって片付けようがなくなります。
 * null のままにして、画面では「未分類」として集めています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材の分類（現04 カテゴリーから検索）")
class IngredientCategoryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredients;

    private static final Path LIST =
            Path.of("src/main/resources/templates/inventory/ingredients.html");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 分類で絞ると、その分類の食材だけになる")
    void filtersByCategory() throws Exception {
        long n = System.nanoTime();
        Ingredient veg = ingredients.save(cat(new Ingredient("分類テスト白菜-" + n, IngredientUnit.GRAM),
                IngredientCategory.VEGETABLE));
        Ingredient meat = ingredients.save(cat(new Ingredient("分類テスト鶏もも-" + n, IngredientUnit.GRAM),
                IngredientCategory.MEAT));

        String html = mockMvc.perform(get("/inventory/ingredients").param("category", "VEGETABLE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("分類テスト白菜-" + n);
        assertThat(html).as("別の分類まで出ている").doesNotContain("分類テスト鶏もも-" + n);

        ingredients.deleteById(veg.getId());
        ingredients.deleteById(meat.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 未分類（NONE）だけを集められる")
    void collectsUnclassified() throws Exception {
        long n = System.nanoTime();
        Ingredient none = ingredients.save(new Ingredient("未分類テスト-" + n, IngredientUnit.GRAM));
        Ingredient veg = ingredients.save(cat(new Ingredient("分類済みテスト-" + n, IngredientUnit.GRAM),
                IngredientCategory.VEGETABLE));

        String html = mockMvc.perform(get("/inventory/ingredients").param("category", "NONE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("未分類テスト-" + n);
        assertThat(html).as("分類済みが未分類に混ざっている").doesNotContain("分類済みテスト-" + n);

        ingredients.deleteById(none.getId());
        ingredients.deleteById(veg.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 名前で探しても分類の絞り込みが外れない")
    void keywordKeepsTheCategory() throws Exception {
        long n = System.nanoTime();
        Ingredient a = ingredients.save(cat(new Ingredient("同名テストねぎ-" + n, IngredientUnit.GRAM),
                IngredientCategory.VEGETABLE));
        Ingredient b = ingredients.save(cat(new Ingredient("同名テストねぎ肉巻き-" + n, IngredientUnit.PIECE),
                IngredientCategory.MEAT));

        String html = mockMvc.perform(get("/inventory/ingredients")
                        .param("q", "同名テストねぎ")
                        .param("category", "MEAT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("同名テストねぎ肉巻き-" + n);
        assertThat(html).as("分類の絞り込みが効いていない").doesNotContain(">同名テストねぎ-" + n + "<");

        // 検索欄は選んでいる分類を hidden で持ち回る。落とすと、名前で
        // 探した瞬間に分類が黙って外れる
        assertThat(Files.readString(LIST)).contains("name=\"category\"");

        ingredients.deleteById(a.getId());
        ingredients.deleteById(b.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 読めない分類が来ても落ちない（全件に倒す）")
    void unknownCategoryFallsBackToAll() throws Exception {
        long n = System.nanoTime();
        Ingredient i = ingredients.save(cat(new Ingredient("倒しテスト-" + n, IngredientUnit.GRAM),
                IngredientCategory.SEAFOOD));

        // 古いブックマークや打ち替えで壊れた URL。404 にすると行き止まりになる
        String html = mockMvc.perform(get("/inventory/ingredients").param("category", "SAKANA"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("倒しテスト-" + n);

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 分類を選ばずに登録できる（仕込み中に手を止めない）")
    void categoryIsOptionalOnCreate() throws Exception {
        String name = "任意テスト-" + System.nanoTime();
        mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", name)
                        .param("unit", "GRAM")
                        .param("category", "")
                        .param("sortOrder", "0"))
                .andExpect(status().is3xxRedirection());

        Ingredient saved = ingredients.findByName(name).orElseThrow();
        assertThat(saved.getCategory()).as("空欄を勝手に埋めている").isNull();
        assertThat(saved.getCategoryLabel()).isEqualTo("未分類");

        ingredients.deleteById(saved.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("分類を選んで登録すると、そのまま保存される")
    void categoryIsSaved() throws Exception {
        String name = "保存テスト-" + System.nanoTime();
        mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", name)
                        .param("unit", "GRAM")
                        .param("category", "SEASONING")
                        .param("sortOrder", "0"))
                .andExpect(status().is3xxRedirection());

        Ingredient saved = ingredients.findByName(name).orElseThrow();
        assertThat(saved.getCategory()).isEqualTo(IngredientCategory.SEASONING);
        assertThat(saved.getCategoryLabel()).isEqualTo("調味料・ソース");

        ingredients.deleteById(saved.getId());
    }

    @Test
    @DisplayName("★ 分類の識別子は変えない（DB の文字列と対応が切れる）")
    void enumNamesAreStable() {
        // ここに入っている名前がそのまま ingredient.category に保存される。
        // 識別子を変えると、既存の行の分類が読めなくなる（IngredientUnit と同じ約束）
        assertThat(java.util.Arrays.stream(IngredientCategory.values())
                .map(Enum::name).toList())
                .containsExactly("VEGETABLE", "MEAT", "SEAFOOD", "FLOUR", "EGG_DAIRY",
                        "SEASONING", "DRINK", "SUPPLIES", "OTHER");
        // 未分類を enum に足さないこと。null と OTHER は意味が違う
        assertThat(java.util.Arrays.stream(IngredientCategory.values()).map(Enum::name).toList())
                .doesNotContain("UNCLASSIFIED", "NONE");
    }

    private static Ingredient cat(Ingredient i, IngredientCategory c) {
        i.setCategory(c);
        return i;
    }
}
