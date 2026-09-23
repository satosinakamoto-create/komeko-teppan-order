package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レシピ・原価表を設計（ト09 731:4147）のとおりにする（2026-09-19、店主の指示）。
 *
 * <p>店主の言葉は「レシピ・原価表で材料の項目があるけど要らないよね？
 * 実装であるけど消して欲しいかな」。設計にも材料の列はありません。
 *
 * <h2>測って出た差</h2>
 * <pre>
 *                実装        設計
 *   列            8（材料あり） 7
 *   列幅          自動        300/140/140/150/140/150/100 ＝ 1120
 *   見出しの高さ   86          64
 *   行の高さ       89          68
 * </pre>
 *
 * <p>見出しが 86px あったのは「原価」＋小さい「税込」の 2 行だったからです。
 * 設計は「原価税込」で 1 行。商品が増えるほど縦に伸びる表なので、
 * 1 行あたりの高さがそのまま効きます。
 *
 * <h2>消したもの・残したもの</h2>
 * <p>材料の列にあった「未登録／金額のみ／N 種類」は消しました。
 * どれもレシピの登録ぐあいの目安で、登録されていないことは
 * 右のボタン（「登録する」か「編集」か）がそのまま言っています。
 *
 * <p><b>「単価不明」だけは商品名の隣へ移しました。</b>
 * あれは見た目の印ではなく<b>金額の警告</b>です。まだ一度も仕入れていない
 * 食材が混ざっている商品で、その分の原価が抜けているため、
 * 右に出ている原価率は<b>実際より低く出ています</b>。
 * 消すと「原価率 26% の商品」に見えたまま、本当は 35% かもしれない、になります。
 *
 * <h2>3 つの幅（実測）</h2>
 * <pre>
 *   1432px  表 1120  300/140/140/150/140/150/100          設計 ト09 と一致
 *   1024px  表 1120  そのまま。.table-wrap が横スクロール   設計 トi11 の但し書きと一致
 *   1920px  表 1444  386.8/180.5/180.5/193.4/…            設計 トp11（387/181/…）と一致
 * </pre>
 */
@DisplayName("レシピ・原価表は設計どおり")
class RecipeTableMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/recipes.html");
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

    @Test
    @DisplayName("★ 材料の列は無い（設計は 7 列）")
    void theIngredientColumnIsGone() throws Exception {
        String html = tpl();

        assertThat(html).as("材料の列が残っている。設計（ト09）は 7 列")
                .doesNotContain("<th>材料</th>");
        assertThat(html).as("材料の列の中身が残っている")
                .doesNotContain("' 種類'");

        // 列見出しはちょうど 7 つ。
        // ★ "<th" で数えないこと。<thead> と <th:block> も引っかかる
        //   （2026-09-19 に実際そうなりました）。"<th " か "<th>" だけ数える。
        int cells = html.split("(?s)<th[ >]", -1).length - 1;
        assertThat(cells).as("列の数が 7 本でない（数えたのは " + cells + " 本）").isEqualTo(7);
    }

    /**
     * ★ 「単価不明」は消さないこと。
     *
     * <p>これは金額の警告です。消すと、実際より低い原価率を正しいものとして読ませます。
     */
    @Test
    @DisplayName("★ 「単価不明」は残っている（金額の警告なので消さない）")
    void theUnknownCostWarningSurvives() throws Exception {
        String html = tpl();

        assertThat(html).as("★ 単価不明の警告が消えている。"
                + "仕入れたことのない食材のぶん原価が抜けていて、"
                + "表示されている原価率は実際より低い。それを知らせる唯一の印")
                .contains("c.isIncomplete()");
        assertThat(html).contains("単価不明");

        // 商品名のセルの中にいること（材料の列と一緒に消えないように）
        int name = html.indexOf("c.menuItem().name");
        int warn = html.indexOf("c.isIncomplete()");
        assertThat(name).isGreaterThan(0);
        assertThat(warn).as("単価不明が商品名のセルの中に無い").isGreaterThan(name);
        assertThat(html.substring(name, warn))
                .as("商品名と単価不明のあいだでセルが閉じている。別の列に残っている")
                .doesNotContain("</td>");
    }

    /**
     * ★ 列の比は設計どおり。合計 1120。
     *
     * <p>★★ 2026-09-22：px → 割合。比は 1120 基準のまま、値だけ % にしました。
     * px のままだと {@code table-layout: fixed} で<b>表が 1120 から縮まず</b>、
     * iPad（本文 897px）で右の列が初期表示から見切れます。
     * 商品の表を 09-20 に % 化したのと同じ直しです。<b>px に戻さないこと。</b>
     */
    @Test
    @DisplayName("★ 列幅は 300/140/150/100（合計 1120）")
    void theColumnWidthsMatchTheDesign() throws Exception {
        String css = css();

        assertThat(css).as(".table--recipes が無い").contains(".table--recipes{table-layout:fixed;}");
        // 設計 300/140/150/100 を 1120 基準の割合で（col-money×3・col-rate×2 で合計 1120）
        assertThat(css).contains(".table--recipes.col-name{width:26.786%;}");   // 300
        assertThat(css).contains(".table--recipes.col-money{width:12.5%;}");  // 140
        assertThat(css).contains(".table--recipes.col-rate{width:13.393%;}");   // 150
        assertThat(css).contains(".table--recipes.col-act{width:8.929%;}");    // 100
        assertThat(css).as("★ 列幅が px に戻っている")
                .doesNotContain(".table--recipes.col-name{width:300px;}");
    }

    /**
     * ★ 商品の列にも幅を書くこと。
     *
     * <p>書かないと合計が 1120 に届かず、入れ物より狭くなって横スクロールが働きません。
     * iPad で商品名の列が 92px まで潰れました（実測）。
     */
    @Test
    @DisplayName("★ 幅は順番ではなくクラスで当てる（列が減っても崩れない）")
    void theWidthsAreKeyedByClassNotOrder() throws Exception {
        String css = css();
        String html = tpl();

        assertThat(css)
                .as("nth-child で幅を当てている。原価の 4 列は店長にしか出ないので、"
                        + "スタッフのときに別の列へ幅が当たる")
                .doesNotContain(".table--recipes th:nth-child");

        for (String cls : new String[]{"col-name", "col-money", "col-rate", "col-act"}) {
            assertThat(html).as(cls + " の目印がテンプレートに無い").contains(cls);
        }
    }

    /**
     * ★ 操作の列の余白を詰めてあること。
     *
     * <p>設計の操作の列は 100px で中身は「編集」という文字ですが、
     * 実装は min-width 88px のボタンです。左右 20px のままだと
     * 中に使えるのが 60px しかなく、28px はみ出します（実測）。
     */
    @Test
    @DisplayName("★ 操作の列はボタンが収まる余白にしてある")
    void theActionButtonFitsInItsColumn() throws Exception {
        assertThat(css())
                .as("操作の列の余白を詰めていない。"
                        + "100 - 20×2 = 60px しか無く、88px のボタンが 28px はみ出す")
                .contains("th.col-act{padding-inline:6px;}");

        assertThat(tpl())
                .as("td 側に col-act が付いていない。"
                        + "列幅は効くが余白が 20px のままで、ボタンがはみ出す")
                .contains("class=\"right col-act\"");
    }

    /** ★ 見出し 64／行 68 は商品の表と同じ流儀（設計どおり）。 */
    @Test
    @DisplayName("★ 見出し 64／行 68")
    void theRowHeightsMatchTheDesign() throws Exception {
        String css = css();
        assertThat(css).as("見出しが 64px でない")
                .contains(".theme-desk.table--recipesth{padding:16px;height:64px;}");
        // ★ 2026-09-20：67 → 68。「区切り線 1px は外に足されるので 67 + 1 = 68」は
        //   誤りでした。実測すると border-collapse: collapse では罫が行の中に描かれ、
        //   行の箱も行の間隔も 67px のままです。仕入れ・スタッフの表（68px）と
        //   1px 食い違っていて、画面を行き来すると行の高さが変わっていました。
        assertThat(css).as("行が 68px でない")
                .contains(".theme-desk.table--recipestd{padding:020px;height:68px;vertical-align:middle;}");
    }

    /**
     * ★ まだ登録していない行だけ赤くする（2026-09-19・店主の指摘
     * 「編集するは緑だけど登録するは赤の方が良いんじゃない？」）。
     *
     * <p>緑＝もう済んでいる／赤＝まだ。実測:
     *
     * <pre>
     *   編集      5 品   文字・枠 rgb(11,122,26)  ＝ #0b7a1a（設計 ト09 と同じ緑）
     *   登録する 95 品   文字・枠 rgb(211,63,63)  ＝ #d33f3f（--danger）
     * </pre>
     *
     * <p><b>設計には答えがありませんでした。</b>ト09 は登録済みの行しか
     * 描いていないので「登録する」の状態が存在しません
     * （ついでに トp11 だけ「開く」の黒で、ト09 の「編集」の緑と食い違っています）。
     *
     * <p><b>塗りつぶしにはしていません。</b>押すと編集画面が開くだけで、
     * 消したり止めたりするボタンではないためです。
     */
    @Test
    @DisplayName("★ 未登録の行だけ赤（済みは緑）")
    void theUnregisteredRowsAreRed() throws Exception {
        String css = css();
        String html = tpl();

        assertThat(html)
                .as("未登録のときだけ赤くする指定が無い")
                .contains("${c.isNothingRegistered()} ? 'recbtn--todo'");

        assertThat(css).as(".recbtn--todo が無い").contains(".recbtn--todo{");
        assertThat(css).as("赤くなっていない")
                .contains(".recbtn--todo{border-color:var(--danger);color:var(--danger);}");

        // ★ .recbtn そのものは緑のまま。食材・在庫の「記録する」も同じクラスで、
        //   あちらに「済み／未済」の区別は無い
        int at = css.indexOf(".recbtn{");
        assertThat(at).as(".recbtn が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf('}', at)))
                .as(".recbtn 自体を赤くしている。食材・在庫の「記録する」まで赤くなる")
                .contains("color:var(--action)");
    }

    /** ★ 列見出しは 1 行（設計は「原価税込」で 1 つの文字列）。 */
    @Test
    @DisplayName("★ 列見出しは 1 行（2 行だと見出しが 86px になる）")
    void theHeadersAreOneLine() throws Exception {
        String html = tpl();
        int head = html.indexOf("<thead>");
        int endHead = html.indexOf("</thead>");
        assertThat(head).isGreaterThan(0);
        assertThat(html.substring(head, endHead))
                .as("列見出しが 2 行に折れている。見出しの高さが 64 → 86px になる")
                .doesNotContain("<br>");
    }
}
