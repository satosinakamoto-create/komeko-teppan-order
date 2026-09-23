package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品切れ・残数の色を設計（ト03 725:4168）にそろえる（2026-09-18、店主の指摘）。
 *
 * <pre>
 *   「品切れの UI がフィグマのデータ通りじゃないから修正して。
 *     線色とかおススメが昔の緑だったりしてるから」
 * </pre>
 *
 * <p><b>実測で 3 か所ちがっていました。</b>
 *
 * <pre>
 *                        実装              設計
 *   表の見出しの下線      #0b7a78（ティール） #e3e3e3
 *   行の下線             #e8e8e8            #e3e3e3
 *   おすすめの地          #ddf0ec（ティール） #ddf0e0
 *   おすすめの文字        #0b7a78（ティール） #0b7a1a
 *   列見出しの字          13px               12px
 * </pre>
 *
 * <p><b>ティールの出どころは 2 つ。</b>
 * <ol>
 *   <li>この画面に直接書かれた {@code #0b7a78} / {@code #ddf0ec}。
 *       表の下線が緑がかっていたのはこれです</li>
 *   <li>{@code --accent}。{@code :root} では黒ですが {@code .theme-desk} では
 *       {@code --green-700}（＝ #0b7a78）になります。カテゴリの選択中が
 *       これを踏んでいました。<b>このセッションで 5 回目の同じ罠です</b></li>
 * </ol>
 *
 * <p>設計側には {@code #0b7a78} も {@code #ddf0ec} も<b>1 か所も出てきません</b>
 * （使われている緑は {@code #0b7a1a} / {@code #ddf0e0} / {@code #3f8e6d} だけ）。
 *
 * <p><b>販売中のチップは直していません。</b>{@code --ok} が
 * {@code #3f8e6d} で、設計と完全に一致していました。
 */
@DisplayName("品切れ・残数の色は設計どおり")
class SoldoutPageMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** セレクタの中身を取り出す。コメントは落とさない（値だけ見るため）。 */
    private String rule(String selector) throws Exception {
        String css = css();
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    /**
     * ★ 表の罫線は {@code #e3e3e3}。
     *
     * <p>見出しの下線が {@code #0b7a78} で、表の頭に緑の線が 1 本入って見えていました。
     * 店主の言う「線色」がこれです。
     */
    @Test
    @DisplayName("★ 表の罫線は #e3e3e3（緑の線を引かない）")
    void theTableRulesAreGrey() throws Exception {
        assertThat(rule(".soldoutpage .table th {"))
                .as("見出しの下線が設計の色でない。ティールだと表の頭に緑の線が入る")
                .contains("border-bottom-color: #e3e3e3;")
                .doesNotContain("#0b7a78");

        assertThat(rule(".soldoutpage .table td {"))
                .as("行の下線が設計の色でない")
                .contains("#e3e3e3");
    }

    /** ★ 列見出しの字は 12px（設計）。実装は 13px でした。 */
    @Test
    @DisplayName("★ 列見出しの字は 12px")
    void theColumnHeadingIsTwelve() throws Exception {
        assertThat(rule(".soldoutpage .table th {"))
                .as("列見出しの字が設計の 12px でない")
                .contains("font-size: 12px;");
    }

    /**
     * ★ おすすめは緑（ティールではない）。
     *
     * <p>設計は 地 {@code #ddf0e0}／文字 {@code #0b7a1a}。実装は
     * 地 {@code #ddf0ec}／文字 {@code #0b7a78} で、どちらもティールでした。
     * <b>1 文字ちがい</b>なので、並べて見ないと気づけません。
     */
    @Test
    @DisplayName("★ おすすめはティールではなく緑")
    void theRecommendedBadgeIsGreen() throws Exception {
        String r = rule(".soldoutpage .table .badge--rec {");

        assertThat(r).as("おすすめにティールがベタ書きされている")
                .doesNotContain("#0b7a78").doesNotContain("#ddf0ec");
        assertThat(r).as("押せない印なので、押せるものと同じ緑の系統で持つ")
                .contains("var(--action-soft)").contains("var(--action)");
    }

    /**
     * ★ カテゴリの選択中も緑。
     *
     * <p>{@code --accent} は {@code .theme-desk} でティールになります。
     * 押せるもの・選ばれているものの色は {@code --action} 系で持つ、が今の決まりです。
     */
    @Test
    @DisplayName("★ カテゴリの選択中は --accent を使わない")
    void theSelectedCategoryIsGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".catpick__item.is-active {");
        assertThat(at).as(".catpick__item.is-active が無い").isGreaterThan(0);
        String r = css.substring(at, css.indexOf("}", at));

        assertThat(r)
                .as("--accent を踏んでいる。.theme-desk では #0b7a78（ティール）になる")
                .doesNotContain("var(--accent)").doesNotContain("var(--accent-soft)");
        assertThat(r).contains("var(--action)");
    }

    /**
     * ★ この画面にティールを二度と書かないこと。
     *
     * <p>コメントは落としてから見ます。「なぜ捨てたか」の記録に色の名前が出るためです。
     */
    @Test
    @DisplayName("★ 品切れ・残数の指定にティールが残っていない")
    void noTealSurvivesOnThisScreen() throws Exception {
        String css = css().replaceAll("(?s)/\\*.*?\\*/", "");

        // ★ ひとかたまりで切り出さないこと。
        //   「.soldoutpage から次の区切りまで」で取ると、後ろにある
        //   ログイン・ダッシュボード・売上の指定まで飲み込みます（1 度踏みました）。
        //   あちらにも #0b7a78 が残っていますが、それはこの画面の話ではありません。
        java.util.List<String> offenders = new java.util.ArrayList<>();
        boolean inSoldout = false;
        for (String line : css.split("\n")) {
            String t = line.trim();
            if (t.contains("{")) {
                inSoldout = t.substring(0, t.indexOf('{')).contains(".soldoutpage");
            }
            if (!inSoldout) continue;
            if (t.contains("#0b7a78") || t.contains("#ddf0ec")) offenders.add(t);
            if (t.contains("}")) inSoldout = false;
        }

        assertThat(offenders)
                .as("品切れ・残数の指定にティール（#0b7a78 / #ddf0ec）が残っている")
                .isEmpty();
    }
}
