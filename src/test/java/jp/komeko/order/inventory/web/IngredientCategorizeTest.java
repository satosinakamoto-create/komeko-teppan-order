package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientCategory;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未分類の食材にまとめて分類を付ける画面（2026-09-07）。
 *
 * <p><b>なぜあるか</b><br>
 * 分類（V14）を後から足したので、既存の食材は全部「未分類」で始まる。
 * 1 件ずつ編集画面を開いて付けると食材の数だけ往復になる。
 *
 * <p><b>いちばん守っているのは「選ばなかった行を触らない」こと。</b>
 * 空のまま保存された行を「その他」で埋めると、分類し忘れと
 * 本当にその他が混ざって、あとから片付けようがなくなる
 * （IngredientCategory の null の約束と同じ）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材の一括分類")
class IngredientCategorizeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredients;

    private final List<Long> made = new ArrayList<>();

    private Ingredient ingredient(String marker, IngredientCategory category) {
        Ingredient i = new Ingredient("一括" + marker + System.nanoTime(), IngredientUnit.GRAM);
        i.setCategory(category);
        i = ingredients.save(i);
        made.add(i.getId());
        return i;
    }

    @AfterEach
    void cleanUp() {
        made.forEach(ingredients::deleteById);
        made.clear();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 選んだ行だけ分類が付き、選ばなかった行は未分類のまま")
    void savesOnlyTheChosenRows() throws Exception {
        Ingredient a = ingredient("A", null);
        Ingredient b = ingredient("B", null);
        Ingredient c = ingredient("C", null);

        mockMvc.perform(post("/inventory/ingredients/categorize").with(csrf())
                        .param("cat-" + a.getId(), "VEGETABLE")
                        .param("cat-" + b.getId(), "MEAT")
                        .param("cat-" + c.getId(), ""))   // あとで決める
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/ingredients/categorize"));

        assertThat(ingredients.findById(a.getId()).orElseThrow().getCategory())
                .isEqualTo(IngredientCategory.VEGETABLE);
        assertThat(ingredients.findById(b.getId()).orElseThrow().getCategory())
                .isEqualTo(IngredientCategory.MEAT);
        // ★ ここが本題。空のままの行を勝手に埋めない
        assertThat(ingredients.findById(c.getId()).orElseThrow().getCategory())
                .as("選ばなかった行が書き換えられた").isNull();
    }

    /**
     * ★ 2026-09-14 に期待を反転させました（設計 ト04d 841:9999 に合わせる）。
     *
     * <p>もとは「未分類だけが並ぶ（分類済みは出ない）」を守っていました。
     * 片付いた行を残さない、という 2026-09-07 の判断です。
     *
     * <p><b>それだと付け間違いを直せません。</b>
     * 「大葉を野菜にしたつもりが調味料になっていた」と気づいても、
     * 大葉はもう未分類ではないので、この画面から消えています。
     * 直すには 1 件ずつ詳細を開くしかなく、まとめて付ける画面の意味が半分無くなる。
     *
     * <p>Figma も全件を並べ、いまの分類を選んだ状態で出し、
     * まだ決まっていない行にだけ「未分類」と添えています。そちらに合わせました。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 分類済みの食材も並ぶ（付け間違いを直せるように）")
    void listsEveryIngredientNotJustTheUnclassified() throws Exception {
        Ingredient none = ingredient("N", null);
        Ingredient done = ingredient("D", IngredientCategory.SEAFOOD);

        String html = mockMvc.perform(get("/inventory/ingredients/categorize"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(none.getName());
        assertThat(html).as("分類済みが並んでいない（付け間違いを直せない）").contains(done.getName());
    }

    /**
     * 入口（一覧の「まとめて分類する →」）は、いまも未分類 0 件で消えます。
     * 消えるのは<b>案内</b>であって画面ではありません——全件が並ぶようになったので、
     * 片付いた後に開いても表は出ます（そこが付け間違いを直す場所）。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧の入口は未分類 0 件で消える（案内が居座らない）")
    void theEntryLinkDisappearsWhenNothingIsLeft() throws Exception {
        ingredient("Z", IngredientCategory.DRINK);

        String board = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long unclassifiedNow = ingredients.findByActiveTrueAndCategoryIsNullOrderBySortOrderAscNameAsc().size();
        if (unclassifiedNow == 0) {
            assertThat(board).doesNotContain("まとめて分類する");
        } else {
            assertThat(board).contains("まとめて分類する");
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("読めない値（改ざん・古い画面）は黙って飛ばし、他の行は保存される")
    void ignoresGarbageValues() throws Exception {
        Ingredient a = ingredient("G", null);

        mockMvc.perform(post("/inventory/ingredients/categorize").with(csrf())
                        .param("cat-" + a.getId(), "VEGETABLE")
                        .param("cat-99999999", "MEAT")        // 存在しない id
                        .param("cat-abc", "MEAT")             // id が数字でない
                        .param("cat-" + a.getId() + "x", "")  // 壊れたキー
                        .param("cat-" + (a.getId() + 1) + "", "SAKANA"))  // 知らない分類
                .andExpect(status().is3xxRedirection());

        assertThat(ingredients.findById(a.getId()).orElseThrow().getCategory())
                .isEqualTo(IngredientCategory.VEGETABLE);
    }
}
