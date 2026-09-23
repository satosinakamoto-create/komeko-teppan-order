package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食材・在庫を設計（ト04 725:4457）にそろえる（2026-09-19、店主の指摘）。
 *
 * <p>店主の言葉は「少ないが赤くないのとカテゴリーから検索が表示されてないから実装して。
 * 他違う所もフィグマデータ通りに実装して」。
 *
 * <h2>測って出た差</h2>
 * <pre>
 *                    実装                             設計
 *   「少ない」の札    灰色塗り・白文字・14px           地 #fdebeb ／ 文字 #d33f3f ／ 11px
 *   見出しの高さ      49.5                             56
 *   行の高さ          67                               72
 *   列幅              240/100/120/130/270/140/120      同じ（直す必要なし）
 *   カテゴリーから検索 無い                             527x48
 * </pre>
 *
 * <h2>「少ない」が灰色だった理由</h2>
 * <p>{@code .badge--soldout} を使っていました。あれは<b>灰色の塗りつぶしに白文字</b>で、
 * お客さま側の「品切れ」で使う形です。押しても無駄だと一目で伝えるための強さがあります。
 *
 * <p>ここで言いたいのは「減ってきた」なので、強さが合っていませんでした。
 * {@code .badge--stop} がちょうど 地 {@code --danger-soft}＝#fdebeb ／
 * 文字 {@code --danger}＝#d33f3f で、<b>設計の値とそのまま一致</b>します。
 *
 * <h2>カテゴリーから検索は入れていません</h2>
 * <p>設計には出ていますが、<b>食材の分類は 2026-09-16 に消した機能</b>です
 * （CLAUDE.md「やらないと決めたこと」）。店主の「分類いるこれ？」から
 * 全参照をあたって、計算にいっさい使われていないことを確かめたうえで落としました。
 * {@code Ingredient} に分類の項目はもうありません。
 *
 * <p>入れ直すと、あのとき消したものを作り直すことになります。
 * 何で絞るのかを決めてもらうまで手を付けません。
 */
@DisplayName("食材・在庫は設計どおり")
class IngredientTableMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/ingredients.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\\s+", "");
    }

    /**
     * ★ 「少ない」「マイナス」は赤い札。
     *
     * <p>灰色の塗りつぶし（{@code .badge--soldout}）に戻さないこと。
     */
    @Test
    @DisplayName("★ 「少ない」は赤い札（地 #fdebeb ／ 文字 #d33f3f）")
    void theLowBadgeIsRed() throws Exception {
        String html = tpl();

        assertThat(html).as("「少ない」の札が無い").contains("少ない");
        assertThat(html)
                .as("★ 「少ない」が灰色の塗りつぶし（.badge--soldout）のまま。"
                        + "設計は 地 #fdebeb ／ 文字 #d33f3f")
                .doesNotContain("badge badge--soldout\">少ない");
        assertThat(html).contains("badge badge--stop\">少ない");
        assertThat(html).as("「マイナス」も同じ扱いにする").contains("badge badge--stop\">マイナス");

        // .badge--stop の色が設計の値であること
        String css = css();
        assertThat(css)
                .as(".badge--stop の色が変わっている。設計は 地 #fdebeb ／ 文字 #d33f3f")
                .contains(".badge--stop{background:var(--danger-soft);color:var(--danger);");
        assertThat(css).as("--danger-soft が #fdebeb でなくなっている")
                .contains("--danger-soft:#fdebeb;");
    }

    /** ★ 札の大きさは 11px（共通の 14px より一段小さい）。 */
    @Test
    @DisplayName("★ 札は 11px（14px だと名前より目立つ）")
    void theBadgeIsSmall() throws Exception {
        assertThat(css())
                .as("札が共通の 14px のまま。行の中で名前より目立つ")
                .contains(".theme-desk.table--stock.badge{font-size:11px;");
    }

    /**
     * ★ 高さは見出し 56／行 72（設計 ト04）。
     *
     * <p>区切り線 1px は外に足されるので、行は 71 で書きます。
     */
    @Test
    @DisplayName("★ 見出し 56／行 72")
    void theHeightsMatchTheDesign() throws Exception {
        String css = css();
        // ★ 2026-09-20：56/71 → 64/68。設計（ト04 食材の表）は 64/68 で、
        //   iPad 版・PC 版の同じ表も 64/68 でした。1432 だけ外れていたものです。
        //   「区切り線ぶん 1px 引く」という書き方もやめました——実測すると
        //   border-collapse: collapse では罫が行の中に描かれ、行の間隔は
        //   箱の高さと同じでした（71 と書けば 71 で描かれる）。
        assertThat(css).as("見出しが 64px でない")
                .contains(".theme-desk.table--stock:has(.recbtn)th{height:64px;}");
        assertThat(css).as("行が 68px でない")
                .contains(".theme-desk.table--stock:has(.recbtn)td{height:68px;}");
    }

    /**
     * ★ 高さは {@code :has(.recbtn)} で絞ること。
     *
     * <p>同じ {@code .table--stock} を「未学習のレシート品名」の表も使っています。
     * あちらは設計に無い別の表で、絞らずに敷くと行が 50 → 72px に太ります。
     */
    @Test
    @DisplayName("★ 高さはボタンのある表だけ（未学習の表まで太らせない）")
    void theHeightOnlyAppliesToTheMainTable() throws Exception {
        String css = css();

        int at = css.indexOf(".theme-desk.table--stocktd{");
        assertThat(at).as("素の .table--stock td が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf('}', at)))
                .as("★ 素の .table--stock td に高さを敷いている。"
                        + "「未学習のレシート品名」の表まで 72px に太る")
                .doesNotContain("height");
    }

    /**
     * ★ 列の比は 240/100/120/130/270/140/120（合計 1120）。
     *
     * <p>設計と実装がもともと一致していた場所です。動かさないための見張り。
     *
     * <p>★★ 2026-09-22：px → 割合。比は 1120 基準のまま、値だけ % にしました。
     *
     * <p>px のままだと {@code table-layout: fixed} で<b>表が 1120 から縮みません</b>。
     * iPad（本文 897px）で右の列が初期表示から見切れていました。
     * 商品の表を 09-20 に % 化したのと同じ直しです。
     *
     * <p><b>px に戻さないこと。</b>戻すと同じはみ出しが戻ります。
     */
    @Test
    @DisplayName("★ 列幅は設計どおりの割合（1120 基準）")
    void theColumnWidthsStayAsDesigned() throws Exception {
        String css = css();
        // 設計 240/100/120/130/270/140/120（合計 1120）を、1120 基準の割合で書く
        String[] widths = {"21.429%", "8.929%", "10.714%", "11.607%", "24.107%", "12.5%", "10.714%"};
        int[] designPx = {240, 100, 120, 130, 270, 140, 120};
        for (int i = 0; i < widths.length; i++) {
            String rule = ".table--stockth:nth-child(" + (i + 1) + "),"
                    + ".table--stocktd:nth-child(" + (i + 1) + "){width:" + widths[i] + ";}";
            assertThat(css).as((i + 1) + " 本目の列幅が " + widths[i]
                    + "（設計 " + designPx[i] + "px）でない").contains(rule);
        }
        assertThat(java.util.Arrays.stream(designPx).sum())
                .as("設計の合計が 1120 でない").isEqualTo(1120);

        // ★ px に戻さないこと（2026-09-22）。fixed では表が 1120 から縮まず、
        //   iPad の本文 897px で右の列が見切れます
        assertThat(css)
                .as("★ 列幅が px に戻っている")
                .doesNotContain(".table--stocktd:nth-child(1){width:240px;}");
    }
}
