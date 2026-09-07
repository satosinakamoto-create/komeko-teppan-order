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

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 画面には未分類だけが並ぶ（分類済みは出ない）")
    void listsOnlyUnclassified() throws Exception {
        Ingredient none = ingredient("N", null);
        Ingredient done = ingredient("D", IngredientCategory.SEAFOOD);

        String html = mockMvc.perform(get("/inventory/ingredients/categorize"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(none.getName());
        assertThat(html).as("分類済みまで並んでいる").doesNotContain(done.getName());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 未分類 0 件なら「全部片付きました」、一覧の入口も消える")
    void zeroStateAndEntryLink() throws Exception {
        // このテストの中では全部に分類を付けておく。
        // ただし他のテストデータに未分類が残っている可能性があるので、
        // 入口の消滅は「自分の食材を分類し終えた後の件数が本文に出る」形で見る
        Ingredient a = ingredient("Z", IngredientCategory.DRINK);

        String board = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long unclassifiedNow = ingredients.findByActiveTrueAndCategoryIsNullOrderBySortOrderAscNameAsc().size();
        if (unclassifiedNow == 0) {
            // 入口が出ていないこと（片付いた店の画面に案内を居座らせない）
            assertThat(board).doesNotContain("まとめて分類する");
            String page = mockMvc.perform(get("/inventory/ingredients/categorize"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(page).contains("全部片付きました");
        } else {
            // 未分類が残っている環境では、入口に件数が出ていること
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
