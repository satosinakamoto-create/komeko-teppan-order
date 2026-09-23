package jp.komeko.order.inventory.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 食材・在庫の「カテゴリーから検索」は<b>商品カテゴリ</b>（2026-09-19、設計 ト04 725:4457）。
 *
 * <p>店主の言葉は「商品カテゴリのこと言ってる」。お品書きの分類を選ぶと、
 * <b>その分類の商品に使われている食材</b>だけが残ります。
 *
 * <h2>食材の分類は作り直していません</h2>
 * <p>あれは 2026-09-16 に消した機能です（CLAUDE.md「やらないと決めたこと」）。
 * 「分類いるこれ？」から全参照をあたって、計算にいっさい使われていないことを
 * 確かめたうえで落としました。{@code Ingredient} に分類の項目はもうありません。
 *
 * <p>ここはレシピ（商品 ↔ 食材）を辿るだけなので、
 * <b>新しく入力してもらうものはありません</b>。
 * 裏を返すと、<b>レシピが登録されていない商品の食材は出てきません</b>。
 * 件数の 0 はそれを正直に出しています。
 *
 * <h2>古いリンクで 400 を返さないこと</h2>
 * <p>ブックマークに {@code ?category=VEGETABLE}（消した分類の名前）が残っている
 * 人がいます。{@code Long} で受けると数字でない値が 400 で突き返され、
 * 一覧そのものが開けません。文字列で受けて、読めなければ黙って無視します。
 * （{@code IngredientCategoryRemovedTest} と両方で見張っています）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材のカテゴリーから検索は商品カテゴリ")
class IngredientCategorySearchTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/ingredients.html");
    private static final Path CTRL =
            Path.of("src/main/java/jp/komeko/order/inventory/web/InventoryIngredientController.java");
    private static final Path SVC =
            Path.of("src/main/java/jp/komeko/order/inventory/service/RecipeService.java");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ カテゴリーから検索が画面に出ている")
    void thePickerIsOnThePage() throws Exception {
        String html = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("カテゴリーから検索が出ていない").contains("カテゴリーから検索");
        assertThat(html).as("開閉の部品（.catpick）を使っていない").contains("catpick");
        assertThat(html).as("全件へ戻る道が無い").contains("すべてのカテゴリ");
    }

    /**
     * ★ 古いリンクで落ちないこと。
     *
     * <p>{@code Long} で受けると 400 になります。実際に一度なりました。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 古いリンク（?category=VEGETABLE）でも 200 で開く")
    void theOldLinkStillOpens() throws Exception {
        mockMvc.perform(get("/inventory/ingredients").param("category", "VEGETABLE"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/inventory/ingredients").param("category", ""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/inventory/ingredients").param("category", "999999"))
                .andExpect(status().isOk());
    }

    /**
     * ★ 受け取りは文字列にしておくこと。
     *
     * <p>{@code Long} に戻すと、古いブックマークが 400 で開けなくなります。
     */
    @Test
    @DisplayName("★ category は文字列で受けている（Long に戻さない）")
    void theParameterIsTakenAsText() throws Exception {
        String java = Files.readString(CTRL).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");

        assertThat(java)
                .as("★ category を Long で受けている。"
                        + "?category=VEGETABLE のような古いリンクが 400 になる")
                .doesNotContain("name = \"category\", required = false) Long");
        assertThat(java).contains("@RequestParam(required = false) String category");
        assertThat(java).as("読めない値を無視していない").contains("NumberFormatException");
    }

    /**
     * ★ ほんとうに絞れていること。
     *
     * <p>画面に欄が出ているだけでは、押しても何も起きない飾りかもしれません。
     * <b>どの商品にも使われていない ID</b>を渡したとき、行が 1 つも残らないことで
     * 「絞り込みが効いている」を確かめます。
     *
     * <p>件数ではなく「記録する」ボタンの数を数えます。
     * あれは食材の行に 1 つずつ付くので、行数と同じです。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ カテゴリを選ぶと、その分類で使う食材だけになる")
    void choosingACategoryActuallyFilters() throws Exception {
        String all = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int allRows = all.split("記録する", -1).length - 1;

        // どの商品にも結び付いていない ID。絞り込みが効いていれば 0 行になる
        String none = mockMvc.perform(get("/inventory/ingredients").param("category", "999999"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int noneRows = none.split("記録する", -1).length - 1;

        assertThat(noneRows)
                .as("★ 絞り込みが効いていない。欄はあるが押しても何も起きない状態"
                        + "（全件 " + allRows + " 行 → 絞り込み後 " + noneRows + " 行）")
                .isLessThan(Math.max(allRows, 1));
        assertThat(noneRows).as("使われていない分類なのに行が残っている").isZero();
    }

    /**
     * ★ 食材そのものに分類を持たせないこと。
     *
     * <p>レシピを辿るだけなら、新しく入力してもらうものはありません。
     */
    @Test
    @DisplayName("★ 食材の分類は作り直していない（レシピを辿るだけ）")
    void noIngredientClassificationIsReintroduced() throws Exception {
        String svc = Files.readString(SVC);
        assertThat(svc).as("レシピから辿る作りになっていない")
                .contains("ingredientIdsByMenuCategory");

        assertThat(tpl()).as("食材に分類を持たせている")
                .doesNotContain("ingredient.category")
                .doesNotContain("ingredientCategory");
    }

    /**
     * ★ 探すときはカテゴリを無視する。
     *
     * <p>「キャベツ」と打った人は、それがどの商品に使われているかを覚えていません
     * （商品・品切れ・残数と同じ考え方）。
     */
    @Test
    @DisplayName("★ 語で探すときはカテゴリを無視する")
    void searchingIgnoresTheCategory() throws Exception {
        String java = Files.readString(CTRL).replace("\r\n", "\n");
        assertThat(java)
                .as("探しているあいだもカテゴリが効いている")
                .contains("keyword.isEmpty() ? categoryId : null");
    }
}
