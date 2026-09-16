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
 * 食材の分類をやめる（2026-09-16、店主の判断）。
 *
 * <p><b>なぜやめたか。</b>店主の「分類いるこれ？」から全参照をあたったところ、
 * {@code IngredientCategory}（野菜・肉・魚介…）を読んでいるのは
 * <b>画面の絞り込みと表示だけ</b>で、計算にはいっさい使われていませんでした。
 *
 * <pre>
 *   TaxRuleService（税率）       … 0 件
 *   StockService（在庫）         … 0 件
 *   ConsumptionService（消費）   … 0 件
 *   RecipeCost（原価）           … 0 件
 *   PurchaseSummary（集計）      … 0 件
 * </pre>
 *
 * <p>分類を 1 件も付けなくても、原価率も在庫も税額もまったく同じ数字が出ます。
 * それなのに食材一覧は「分類が決まっていない食材が N 件あります →
 * まとめて分類する」と催促していました。
 * <b>何も産まない作業を、忙しい日に催促していた</b>ことになります。
 *
 * <p><b>もう 1 つの理由が「カテゴリー」という語の衝突です。</b>
 * 品切れ・残数の「カテゴリーから検索」は<b>商品</b>カテゴリ（お品書きの 14 分類）、
 * 食材・在庫の「カテゴリーから検索」は<b>食材</b>の分類で、
 * 同じサイドバーの中で同じ文言が別のものを指していました。
 * コードでも {@code categoryGroups} という同じ名前が両方で使われていて、
 * <b>作った本人が読み違えました</b>。店員が読み違えないはずがない。
 *
 * <p><b>本当に効いているのは入り数の学習のほうです</b>（{@code ItemAlias}）。
 * レシートの「エリンギ 120」を一度だけ「食材エリンギの 100g」と教えると、
 * 次から自動で在庫に積まれます。<b>教えないと在庫が積まれない</b>ので、
 * こちらは付けないと数字が変わります。探すための札と、
 * 計算に効く紐付けを混同しないこと。
 *
 * <p><b>列も落としました</b>（{@code V17__drop_ingredient_category.sql}）。
 * 本番は {@code ddl-auto: validate} なので、列を残したままフィールドを消すと
 * 起動時に検証が落ちます。片方だけ消さないこと。
 *
 * <p><b>もし「魚のなにか」で探したくなったら</b>、消したのは
 * 「手で札を貼る仕組み」です。作り直すなら、レシート読取が既に Claude を
 * 呼んでいるので、その JSON スキーマに 1 項目足して<b>自動で埋める</b>ほうから
 * 検討してください（人に貼らせる画面をもう一度作らない）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材の分類はやめた")
class IngredientCategoryRemovedTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path TPL = Path.of("src/main/resources/templates");

    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- 消えたもの

    @Test
    @DisplayName("★ enum とテンプレートが消えている")
    void theSourcesAreGone() {
        assertThat(Path.of("src/main/java/jp/komeko/order/inventory/domain/IngredientCategory.java"))
                .as("分類の enum が残っている").doesNotExist();
        assertThat(TPL.resolve("inventory/ingredient-categorize.html"))
                .as("一括分類の画面が残っている").doesNotExist();
    }

    @Test
    @DisplayName("★ 食材一覧から絞り込みと催促が消えている")
    void theListDropsTheFilterAndTheNudge() throws Exception {
        String html = tpl("inventory/ingredients.html");
        assertThat(html).as("まとめて分類への入口が残っている")
                .doesNotContain("/inventory/ingredients/categorize");
        assertThat(html).as("未分類の件数で急かす表示が残っている")
                .doesNotContain("unclassifiedCount");
        assertThat(html).as("分類での絞り込みが残っている")
                .doesNotContain("categoryGroups");
        assertThat(html).as("「カテゴリーから検索」が残っている（商品カテゴリと紛らわしい語）")
                .doesNotContain("カテゴリーから検索");
    }

    @Test
    @DisplayName("★ 食材のフォームと詳細から分類が消えている")
    void theFormAndDetailDropTheCategory() throws Exception {
        assertThat(tpl("inventory/ingredient-form.html"))
                .as("分類の選択欄が残っている").doesNotContain("分類");
        assertThat(tpl("inventory/ingredient-detail.html"))
                .as("補足に分類が残っている").doesNotContain("ingredient.category");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ /inventory/ingredients/categorize は 404")
    void theRouteIsGone() throws Exception {
        mockMvc.perform(get("/inventory/ingredients/categorize"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 壊さなかったもの

    /**
     * ブックマークや履歴に {@code ?category=VEGETABLE} が残っている人がいます。
     * 知らない問い合わせ文字は<b>黙って無視して一覧を出す</b>のが正しい振る舞いで、
     * 500 で突き放さないこと。卓・QR を分けたときと同じ判断です。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 古いリンク（?category=…）を開いても落ちない")
    void theOldFilterLinkStillAnswers() throws Exception {
        mockMvc.perform(get("/inventory/ingredients").param("category", "VEGETABLE"))
                .andExpect(status().isOk());
    }

    /** 名前で探す欄は残します。分類と違って、これは日常的に使います。 */
    @Test
    @DisplayName("★ 名前で探す欄は残っている")
    void theKeywordSearchSurvives() throws Exception {
        assertThat(tpl("inventory/ingredients.html"))
                .as("食材を探す欄まで消している").contains("searchbox");
    }

    /**
     * {@code .catpick}（カテゴリーの箱）の CSS は消しません。
     * 品切れ・残数が<b>商品</b>カテゴリの絞り込みで使っています。
     * 食材側だけ消すつもりで CSS を消すと、厨房の画面が崩れます。
     */
    @Test
    @DisplayName("★ 品切れ・残数の商品カテゴリ絞り込みは無事")
    void theStockScreenKeepsItsOwnCategoryPicker() throws Exception {
        assertThat(tpl("kitchen/stock.html"))
                .as("厨房側のカテゴリ絞り込みまで消している").contains("catpick");
    }
}
