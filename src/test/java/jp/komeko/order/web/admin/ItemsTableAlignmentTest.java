package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品の表の列ごとの揃え方（2026-09-19、設計 ト10 731:4408）。
 *
 * <p>店主の指摘「表のレイアウトがずれてる」。測ったら、
 * <b>見出しが全部左なのに中身は右や中央</b>で、見出しと数字が
 * 1 本の線に乗っていませんでした。
 *
 * <pre>
 *   列            見出し（前）  行（前）   設計
 *   商品名         左           左        左
 *   カテゴリ       左           左        左
 *   価格（税込）   左           右 ★      左
 *   原価          左 ★         右 ★      中央
 *   掲載          左 ★         左寄り ★   中央
 *   販売          左 ★         左寄り ★   中央
 *   並び          左 ★         左寄り ★   中央
 * </pre>
 *
 * <h2>クラスで当てること</h2>
 * <p>原価の列は在庫モジュールを切った店では出ません（{@code th:if}）。
 * {@code nth-child} で当てると、列が 1 本減った瞬間に別の列へ効きます。
 *
 * <h2>詳細度で 1 回踏みました</h2>
 * <p>{@code .table td.num} が {@code text-align: right} を持っていて (0,2,1) あります。
 * クラスだけの {@code .table--items .col-price} は (0,2,0) で<b>負けます</b>。
 * 見出しだけ直って行が右のまま残りました。{@code th}/{@code td} を付けて
 * (0,2,1) にし、あちらより後ろに置いて勝たせています。
 *
 * <h2>まだ収まっていない列がある</h2>
 * <p>中央ぞろえの列は左右の余白を 8px に詰めました（中央では余白は位置に効かず、
 * 使える幅を削るだけのため）。それでも原価と並びは中身のほうが広いままです。
 *
 * <pre>
 *   列     セル  中に使える  中身     はみ出し
 *   原価   120      104     114.8     10.8
 *   掲載   130      114     114        0
 *   並び   120      104     131.3     27.3   （↑↓ のボタン＋数字）
 * </pre>
 *
 * <p>列を広げると設計の合計 1120 が崩れ、ボタンを小さくすると押しにくくなるので、
 * <b>そこは店主に決めてもらうまで触っていません</b>。
 */
@DisplayName("商品の表の揃え方")
class ItemsTableAlignmentTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/items.html");
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

    /** ★ 見出しと中身の両方に、同じ列の目印が付いていること。 */
    @Test
    @DisplayName("★ 列の目印が見出しと中身の両方に付いている")
    void everyColumnIsLabelledOnBothRows() throws Exception {
        String html = tpl();

        // 見出し 6 本
        // ★ 2026-09-20：col-order（並び）を外しました。つまみをやめたためです。
        for (String cls : new String[]{"col-name", "col-cat", "col-price",
                                       "col-cost", "col-state", "col-act"}) {
            assertThat(html).as("見出しに " + cls + " が無い").contains("th class=\"" + cls + "\"");
        }
        // ★ 2026-09-20：販売の列を編集に置き換えたので col-state は 1 つだけ
        //   （店主の判断「販売の品切れは品切れ残数で調整出来るからそれを編集にすればいい」）
        assertThat(html.split("td class=\"col-state\"", -1).length - 1)
                .as("掲載に col-state が付いていない").isEqualTo(1);
        assertThat(html).as("編集の列に col-act が付いていない").contains("td class=\"col-act\"");
        assertThat(html).as("価格の中身に目印が無い").contains("td class=\"num col-price\"");
        assertThat(html).as("原価の中身に目印が無い").contains("td class=\"num col-cost\"");
        // ★ 2026-09-20：並びの列は外しました。戻さないこと——
        //   並べ替えた表でドラッグさせると、落とした位置の意味が決まりません。
        assertThat(html).as("★ 並びの列が戻っている").doesNotContain("col-order");
    }

    /**
     * ★ 揃え方が設計どおり。
     *
     * <p>商品名・カテゴリ・価格は左、原価・掲載・販売・並びは中央。
     */
    @Test
    @DisplayName("★ 左 3 列・中央 4 列（設計 ト10）")
    void theAlignmentMatchesTheDesign() throws Exception {
        String css = css();

        assertThat(css).as("左ぞろえの 3 列の指定が無い")
                .contains(".table--itemsth.col-name,.table--itemstd.col-name,"
                        + ".table--itemsth.col-cat,.table--itemstd.col-cat,"
                        + ".table--itemsth.col-price,.table--itemstd.col-price{text-align:left;}");
        assertThat(css).as("中央ぞろえの 4 列の指定が無い")
                .contains(".table--itemsth.col-cost,.table--itemstd.col-cost,"
                        + ".table--itemsth.col-state,.table--itemstd.col-state,"
                        + ".table--itemsth.col-act,.table--itemstd.col-act{text-align:center;}");
    }

    /**
     * ★ セレクタに th / td を付けること。
     *
     * <p>付けないと {@code .table td.num}（0,2,1）に負けて、行だけ右のまま残ります。
     */
    @Test
    @DisplayName("★ 詳細度を .table td.num に勝たせてある")
    void theSelectorBeatsTheNumRule() throws Exception {
        String css = css();

        int num = css.indexOf(".tabletd.num,.tableth.num{text-align:right;");
        int mine = css.indexOf(".table--itemsth.col-price,.table--itemstd.col-price{text-align:left;}");
        assertThat(num).as(".table td.num の規則が無い").isGreaterThan(0);
        assertThat(mine).as("列ごとの揃えの規則が無い").isGreaterThan(0);
        assertThat(mine)
                .as("★ 列ごとの揃えが .table td.num より前にある。"
                        + "同じ詳細度 (0,2,1) なので、前にあると負けて行だけ右に残る")
                .isGreaterThan(num);

        // クラスだけ（0,2,0）で書いていないこと
        assertThat(css)
                .as("th/td を付けずにクラスだけで書いている。.table td.num に負ける")
                .doesNotContain(".table--items.col-price{text-align:left");
    }

    /**
     * ★ 中央ぞろえの列だけ余白を詰めてあること。
     *
     * <p>中央では余白は位置に効かず、使える幅を削るだけです。
     * 左ぞろえの列は字の始まる位置を決めるので詰めません。
     */
    @Test
    @DisplayName("★ 中央の列だけ余白 8px（左の列は詰めない）")
    void onlyTheCentredColumnsAreTightened() throws Exception {
        String css = css();

        assertThat(css).as("中央の列の余白を詰めていない")
                .contains(".table--itemsth.col-cost,.table--itemstd.col-cost,"
                        + ".table--itemsth.col-state,.table--itemstd.col-state,"
                        + ".table--itemsth.col-act,.table--itemstd.col-act{padding-inline:8px;}");

        assertThat(css)
                .as("左ぞろえの列まで詰めている。字の始まる位置が変わる")
                .doesNotContain(".table--itemsth.col-name,.table--itemstd.col-name,"
                        + ".table--itemsth.col-cat,.table--itemstd.col-cat,"
                        + ".table--itemsth.col-price,.table--itemstd.col-price{padding-inline:8px;}");
    }
}
