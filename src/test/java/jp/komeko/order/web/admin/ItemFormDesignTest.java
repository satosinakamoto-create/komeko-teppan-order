package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品を追加・編集する画面の寸法（設計 08-2 315:1983 ／ 08-3 318:2012）。
 *
 * <p><b>この画面は一度「実装したつもり」で外しました（2026-09-07）。</b><br>
 * 1 回目は画面写真だけを見て組み、構造は近いのに見た目が別物でした。
 * カードの角丸・内側の余白・チップの大きさ・ラベルの色——
 * どれも並べないと気づけない差で、指摘されるまで直せていません。
 *
 * <p>2 回目は {@code get_design_context} で<b>設計データそのもの</b>を読み、
 * 数値を写しました。ここはその数値を留め置く場所です。
 * 数字を直すときは、必ず Figma 側と両方を直してください。
 */
@DisplayName("商品フォームの寸法（設計 08-2 / 08-3）")
class ItemFormDesignTest {

    private static final Path HTML =
            Path.of("src/main/resources/templates/admin/item-form.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String rule(String selector) throws Exception {
        String css = Files.readString(CSS);
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at));
    }

    @Test
    @DisplayName("★ 入力カードは 角丸 12・内側 32・中の間 28（設計 316:1993）")
    void cardMetrics() throws Exception {
        String rule = rule(".formcard {");
        assertThat(rule).contains("border-radius: 12px;");
        assertThat(rule).contains("padding: 32px;");
        assertThat(rule).contains("gap: 28px;");
    }

    @Test
    @DisplayName("★ カードに見出しの帯を付けない（設計に無い章立てを作らない）")
    void cardHasNoHead() throws Exception {
        String html = Files.readString(HTML).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html).as("「基本情報」の帯が戻っている").doesNotContain("基本情報");
        assertThat(html).as("写真が別カードに分かれている").doesNotContain("商品画像");
    }

    @Test
    @DisplayName("★ 選択チップは 上下 11・左右 20・角丸 8・文字 15（設計 314:2128）")
    void statePickMetrics() throws Exception {
        String rule = rule(".statepick__item span {");
        assertThat(rule).contains("padding: 11px 20px;");
        assertThat(rule).contains("border-radius: 8px;");
        assertThat(rule).contains("font-size: 15px;");
        // 選択中だけ太字。未選択は Regular（設計 314:2133）
        assertThat(rule).contains("font-weight: 400;");
        assertThat(rule(".statepick__item input:checked + span {")).contains("font-weight: 700;");
        // 選択肢の間は 12px
        assertThat(rule(".statepick {")).contains("gap: 12px;");
    }

    @Test
    @DisplayName("★ 押せない選択肢は消さずに薄く出す")
    void disabledChoicesStayVisible() throws Exception {
        // 消すと、何をすれば選べるようになるのかが画面から読み取れない
        assertThat(rule(".statepick__item input:disabled + span {")).contains("opacity: .5;");
        String html = Files.readString(HTML);
        assertThat(html).as("掲載中を押せなくする条件が無い")
                .contains("value=\"published\" th:disabled=\"${notReady}\"");
    }

    @Test
    @DisplayName("★ 「掲載する」が押せないときは不透明度 40%（設計 315:2150）")
    void disabledPublishButtonIsFaded() throws Exception {
        assertThat(rule(".formhead__actions .btn:disabled {")).contains("opacity: .4;");
    }

    @Test
    @DisplayName("★ 編集中の札は 幅 86・角丸 4・状態/案内 薄（設計 298:2063）")
    void draftTagMetrics() throws Exception {
        String rule = rule(".statetag {");
        assertThat(rule).contains("width: 86px;");
        assertThat(rule).contains("padding: 6px 8px;");
        assertThat(rule).contains("border-radius: 4px;");
        assertThat(rule).contains("font-size: 14px;");
        // --info-soft は #edeefc で、設計の「状態/案内 薄」と同じ値。
        // 生の 16 進を書かず、トークンから引く
        assertThat(rule).contains("background: var(--info-soft);");
        assertThat(rule).contains("color: var(--info);");
    }

    @Test
    @DisplayName("★ 入力のラベルは補足色、写真・掲載・販売の見出しは本文色（設計 316:1994）")
    void labelColours() throws Exception {
        String html = Files.readString(HTML);
        for (String field : new String[]{"name", "categoryId", "price", "sortOrder"}) {
            assertThat(html).as(field + " のラベルが本文色のまま")
                    .contains("class=\"label label--soft\" for=\"" + field + "\"");
        }
        assertThat(rule(".label--soft {")).contains("color: var(--text-muted);");
    }

    @Test
    @DisplayName("★ 写真の見本は 160×120・破線・角丸 8（設計 319:2044）")
    void photoPlaceholderMetrics() throws Exception {
        // ★ 複数行のセレクタで探さないこと。
        //   改行コードが環境で変わるので、素直に片方の行から探す
        String rule = rule(".photopick__preview {");
        assertThat(rule).contains("width: 160px;");
        assertThat(rule).contains("height: 120px;");
        assertThat(rule).contains("border-radius: 8px;");
        assertThat(rule(".photopick__empty {")).contains("border: 1px dashed var(--border);");
    }

    @Test
    @DisplayName("★ 並びの欄は新規でも出す（設計に「95」が入っている）")
    void sortOrderIsAlwaysShown() throws Exception {
        String html = Files.readString(HTML).replaceAll("(?s)<!--.*?-->", "");
        int at = html.indexOf("*{sortOrder}");
        assertThat(at).as("並びの欄が無い").isGreaterThan(-1);
        // 「編集のときだけ」の条件が付いていないこと
        String block = html.substring(html.lastIndexOf("<div class=\"field\"", at), at);
        assertThat(block).as("並びが編集のときしか出ない").doesNotContain("itemForm.id != null");
    }

    @Test
    @DisplayName("★ 打たれた並びの数字を無視しない")
    void typedSortOrderIsHonoured() throws Exception {
        // 欄を出しておいて保存時に上書きすると、
        // 入力できるのに反映されない欄になる
        String java = Files.readString(
                Path.of("src/main/java/jp/komeko/order/web/admin/AdminMenuItemController.java"));
        assertThat(java).contains("if (form.getSortOrder() == null) {");
        // 新規フォームは「どのカテゴリでも末尾になる番号」を入れておく
        assertThat(java).contains("form.setSortOrder(menuService.nextItemSortOrderAnywhere());");
    }

    @Test
    @DisplayName("ファイル選択は隠すが、キーボードでは辿れる")
    void fileInputStaysReachable() throws Exception {
        // display:none にすると、キーボードでも支援技術でも辿り着けなくなる
        String html = Files.readString(HTML);
        assertThat(html).contains("class=\"visually-hidden\" type=\"file\" id=\"image\"");
        assertThat(rule(".visually-hidden {")).doesNotContain("display: none");
    }
}
