package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品切れ・残数を Figma「ト03 品切れ・残数（トンマナRender）」725:4168 にそろえる（2026-09-15）。
 *
 * <p><b>きっかけ。</b>店主から「トi06（iPad・PC）にカテゴリーから検索ボックスが無い」と
 * 指摘がありました。調べると iPad 版（736:5478）と PC 版（736:5714）は
 * <b>Render 版より前の世代</b>で、検索ボックスだけでなく
 * 品切れ中のピル・節見出し・操作列（品切れにする）・残数の入力欄まで揃って欠けていました。
 * Figma の 2 枚を Render 版に合わせて描き直したうえで、実装側の食い違いをここで固定します。
 *
 * <p><b>Figma の本文（725:4172）の構成</b>
 * <pre>
 *   見出し／ページ   1120 × 77   … 題「品切れ・残数管理」＋ 赤ピル「品切れ中 N 品」＋「／ 掲載中 N 品」
 *   しぼり込み        1096 × 48   … 「商品を探す」527 ＋ 間 42 ＋「カテゴリーから検索」527
 *   節見出し          1120 × 46   … カテゴリ名 ＋ 件数
 *   品目の表          1120        … 商品404 / 価格100 / 状態110 / 残数240 / 操作170
 * </pre>
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("品切れ・残数は Figma ト03 どおり")
class SoldoutMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/kitchen/stock.html");

    private String read() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n");
    }

    /**
     * コメントを落とした HTML。<b>doesNotContain は必ずこちらに掛けること。</b>
     * このプロジェクトは意図を注意書きに残す方針なので、
     * 禁止したい文字列がそのまま注意書きに出てきます。
     */
    private String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ しぼり込みは 2 つ（商品を探す ＋ カテゴリーから検索）")
    void bothFilterBoxesArePresent() throws Exception {
        String m = withoutComments(read());
        assertThat(m).as("探す欄が無い").contains("placeholder=\"商品を探す\"");
        assertThat(m).as("カテゴリー選択が無い").contains("class=\"catpick\"");
    }

    /**
     * 選んでいないときの文言。設計は「カテゴリーから検索」で、
     * 食材・在庫（ト07）も同じ。実装だけ「カテゴリを選ぶ」でした。
     */
    @Test
    @DisplayName("★ 未選択の文言は「カテゴリーから検索」")
    void theIdleLabelMatchesTheDesign() throws Exception {
        String m = withoutComments(read());
        assertThat(m).contains("'カテゴリーから検索'");
        assertThat(m).as("旧文言が残っている").doesNotContain("'カテゴリを選ぶ'");
    }

    @Test
    @DisplayName("★ 見出しは題＋品切れ中のピル＋掲載中の件数")
    void theHeadCarriesTheSoldoutChip() throws Exception {
        String m = withoutComments(read());
        assertThat(m).contains("品切れ・残数管理");
        assertThat(m).as("品切れ中のピルが無い").contains("'品切れ中 ' + ${soldOutCount}");
        assertThat(m).as("掲載中の件数が無い").contains("掲載中 ");
    }

    @Test
    @DisplayName("★ 表は 5 列（商品・価格・状態・残数・操作）")
    void theTableKeepsAllFiveColumns() throws Exception {
        String m = withoutComments(read());
        // ★ 列見出しには class が付くものがある（価格は .num で右寄せ）。
        //   <th>価格</th> と決め打ちすると、そこだけ見つからず落ちます
        for (String th : new String[]{"商品", "価格", "状態", "残数（数量限定の品だけ）", "操作"}) {
            assertThat(m).as(th + " の列が無い").containsPattern("<th[^>]*>" + th + "</th>");
        }
    }

    /**
     * 節見出し（選んでいるカテゴリ名と件数）。Figma では表の真上にあります。
     * 「いま何を見ているか」は、カテゴリを選んで絞ったあとに効いてきます。
     */
    @Test
    @DisplayName("★ 表の上に節見出し（カテゴリ名＋件数）")
    void theSectionHeadSitsAboveTheTable() throws Exception {
        String m = withoutComments(read());
        int sec = m.indexOf("soldout-sec");
        int table = m.indexOf("<table");
        assertThat(sec).as("節見出しが無い").isGreaterThan(0);
        assertThat(sec).as("節見出しが表より下にある").isLessThan(table);
    }

    /**
     * ★ しぼり込みの右端は、下の表の右端とそろえる（2026-09-15、店主の指摘）。
     *
     * <p>実装には {@code max-width: 1096px} が入っていました。Figma の Render 版
     * （725:4180）も 1096 なので<b>一致してはいた</b>のですが、
     * 同じ画面の節見出しと表は 1120（本文の内側いっぱい）で、
     * <b>しぼり込みだけ右に 24px の余白</b>が残る形でした。
     * 描き間違いと判断して、表に合わせています
     * （iPad・PC 版を Render 版に合わせて描き直したとき、そちらは
     * しぼり込みと表を同じ幅にしてあり、そこで食い違いが見えました）。
     *
     * <p>固定値ではなく上限を外すのが正解です。広い画面（32 節）では
     * 本文が広がり、表は {@code width:100%} で追従します。
     * しぼり込みだけ 1096 で止まると、画面が広いほどずれが目立ちます。
     */
    @Test
    @DisplayName("★ しぼり込みの幅は表とそろえる（固定の上限を置かない）")
    void theFilterRowIsAsWideAsTheTable() throws Exception {
        String css = Files.readString(
                Path.of("src/main/resources/static/css/app.css")).replace("\r\n", "\n");
        String withoutComments = css.replaceAll("(?s)/\\*.*?\\*/", "");

        int at = withoutComments.indexOf(".soldoutpage .stockfind {");
        assertThat(at).as(".soldoutpage .stockfind が無い").isGreaterThan(0);
        assertThat(withoutComments.substring(at, withoutComments.indexOf("}", at)))
                .as("固定の上限が残っていて、表より狭いまま")
                .doesNotContain("max-width");
    }
}
