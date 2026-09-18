package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店舗設定を設計（ト14 732:4677）に寄せる（2026-09-19、店主の指示）。
 *
 * <p><b>まず、設計と実装は同じものではありません。</b>設計は 8 項目を 2 列に
 * 並べただけの絵で、実装にある次のものがありません。
 *
 * <pre>
 *   受付の一時停止（非常ブレーキ）
 *   「片付け待ち」を挟む設定
 *   各項目の説明文（「画面の上部・ブラウザのタブ・注文控えに出る名前です」など）
 *   お客さまへのご案内／受付停止中のメッセージ／閉店時刻
 * </pre>
 *
 * <p>そのまま実装すると<b>機能が消えます</b>。なので採ったのは設計の
 * <b>並べ方</b>だけです。中身は 1 つも減らしていません。
 *
 * <h2>設計から採ったもの</h2>
 * <ol>
 *   <li><b>題をいちばん上に。</b>設計は「店舗設定」が先頭。実装は受付の非常ブレーキが
 *       上にあり、題が 271px も下にありました（他の画面は 48px）。
 *       全画面の中でここだけ突出していた原因です</li>
 *   <li><b>対になる項目を 2 列に。</b>設計は 4 組とも 2 列。
 *       実装で 2 列だったのは「営業開始／ラストオーダー」だけでした</li>
 * </ol>
 *
 * <h2>採らなかったもの</h2>
 * <p>「消費税率／テーブルチャージ」を隣同士にすること。設計では並んでいますが、
 * 実装では<b>別のカード</b>（会計と注文番号／テーブルチャージと深夜料金）にいます。
 * 隣にするにはカードの分け方を変えることになり、それは並べ方ではなく
 * <b>意味のまとまりを変える</b>話なので、店主に確認するまで動かしません。
 */
@DisplayName("店舗設定は設計の並べ方どおり")
class SettingsMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/settings.html");

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 題がいちばん上にあること。
     *
     * <p>ここだけ題が 271px 下にありました。他の画面は 48px です。
     * 設計も題が先頭なので、そこにそろえます。
     */
    @Test
    @DisplayName("★ 題は本文のいちばん上（受付の非常ブレーキより前）")
    void theTitleComesFirst() throws Exception {
        String html = body();

        int title = html.indexOf("page-head__title");
        int brake = html.indexOf("toggle-accepting");

        assertThat(title).as("題が無い").isGreaterThan(0);
        assertThat(brake).as("受付の一時停止が無い").isGreaterThan(0);
        assertThat(title)
                .as("題が受付の一時停止より後ろにある。"
                        + "この画面だけ題が本文のずっと下に沈む（実測 271px・他は 48px）")
                .isLessThan(brake);
    }

    /**
     * ★ 対になる項目は 2 列に。
     *
     * <p>設計（ト14）は 4 組とも 2 列です。
     */
    @Test
    @DisplayName("★ 店舗名とキャッチコピーは 2 列")
    void theShopNameAndTaglineSitSideBySide() throws Exception {
        String html = body();

        int name = html.indexOf("id=\"shopName\"");
        int tag = html.indexOf("id=\"tagline\"");
        assertThat(name).as("店舗名が無い").isGreaterThan(0);
        assertThat(tag).as("キャッチコピーが無い").isGreaterThan(name);

        // 2 つのあいだに grid--2 を閉じるタグが無いこと＝同じ 2 列の中にいる
        String between = html.substring(name, tag);
        int grid = html.lastIndexOf("grid grid--2", name);
        assertThat(grid).as("店舗名が 2 列の中にいない").isGreaterThan(0);
        assertThat(between)
                .as("店舗名とキャッチコピーが別の行にいる。設計は 2 列")
                .doesNotContain("</div>\n        </div>");
    }

    /** ★ 説明文は消さないこと。設計には無いが、実装の親切さはここにある。 */
    @Test
    @DisplayName("★ 各項目の説明文は残っている")
    void theHelpTextSurvives() throws Exception {
        String html = body();
        assertThat(html).as("店舗名の説明が消えている")
                .contains("画面の上部・ブラウザのタブ・注文控えに出る名前です");
        assertThat(html).as("キャッチコピーの説明が消えている")
                .contains("店舗名の下に小さく出る一言です");
    }

    /**
     * ★ 設計に無い機能を落とさないこと。
     *
     * <p>設計は 8 項目だけの絵です。そのまま実装すると、営業中に使う
     * 非常ブレーキまで消えます。
     */
    @Test
    @DisplayName("★ 設計に無い機能（非常ブレーキ・片付け待ち）は残す")
    void nothingIsLost() throws Exception {
        String html = body();
        assertThat(html).as("受付の一時停止が消えている").contains("toggle-accepting");
        assertThat(html).as("片付け待ちの設定が消えている").contains("cleanupAfterCheckout");
        assertThat(html).as("受付停止中のメッセージが消えている").contains("closedMessage");
    }
}
