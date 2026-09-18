package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下の余白と題の字を、店舗管理と税理士でそろえる（2026-09-18、店主の指摘）。
 *
 * <pre>
 *   「上下の余白も統一感ないんだけど調べて」
 *   「ホール会計のフォントまだ統一されてないくない？ちゃんと全体測って」
 * </pre>
 *
 * <p>全 22 画面を 1432px で実測して、4 つ見つかりました。
 *
 * <h2>① 題の行間が 2 種類あった</h2>
 * <pre>
 *   .page-head__title       32px / 行間 36px   14 枚
 *   .section-title__text    32px / 行間 45px    8 枚 ← 指定が無く 1.4 倍＝44.8px
 * </pre>
 * 字は同じ 32px なので、<b>行の高さだけ 9px 違って大きく見えて</b>いました。
 * 店主が「ホールのフォントが統一されてない」と言ったのはこれです。
 *
 * <h2>② 税理士 5 画面だけリズムに乗っていなかった</h2>
 * <pre>
 *   ブロック間   税理士 16px ／ 他 48px
 *   器の上～題   税理士 83px ／ 他 55px
 *   題～次       税理士 27px ／ 他 71px
 * </pre>
 *
 * <p>原因は<b>構造</b>でした。ブロックの間を決めているのは
 * {@code .theme-desk .staff-main > main { display: flex; gap: 48px }} ですが、
 *
 * <pre>
 *   店舗管理  .staff-main &gt; main                    … 直下なので効く
 *   税理士    main.staff-main &gt; .container &gt; main   … .container が挟まって効かない
 * </pre>
 *
 * <p>あわせて {@code <main>} が入れ子（HTML として不正）にもなっていました。
 * 店舗管理が外側を {@code <div>} にしているのは、まさにそれを避けるためです。
 *
 * <p>構造をそろえたあとも 16px ずつ足りず、それは<b>題の行の上下 padding</b>でした
 * （.page-head は 16・16、.section-title は 0・0）。
 *
 * <h2>③ 品切れの入力欄が 14px</h2>
 * CLAUDE.md の「入力欄は 16px 未満にしない」に反していました。
 * iOS が 16px 未満の入力欄で画面を勝手に拡大します。
 *
 * <h2>④ 節の補足が 16 / 13 / 14px</h2>
 * 16px にそろえました。
 *
 * <p><b>直していないもの。</b>ホール・会計の節見出し（20px・字間 2px）は
 * 立って読む画面向けの意図的な指定に見えるので、店主の判断で残しています。
 * 売上の 20px と品切れの 24px のブロック間も、理由つきの上書きなのでそのままです。
 */
@DisplayName("上下の余白と題の字は全画面で共有")
class VerticalRhythmIsSharedTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path LEDGER = Path.of("src/main/resources/templates/layout/accountant.html");
    private static final Path STAFF = Path.of("src/main/resources/templates/layout/staff.html");

    /** コメントを落とした CSS。注記に同じ語が出るので、必ずこちらで探すこと。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private String rule(String selector) throws Exception {
        String css = css();
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    /** ★ 題の行間は 36px。字が同じでも、行が高いと大きく見える。 */
    @Test
    @DisplayName("★ .section-title__text の題も行間 36px")
    void theTitleLineHeightIsShared() throws Exception {
        assertThat(rule(".theme-desk h1.section-title__text {"))
                .as("行間の指定が無い。1.4 倍（44.8px）になり、"
                        + ".page-head__title より行が 9px 高くなる")
                .contains("line-height: 36px");
    }

    /**
     * ★ 税理士の器は {@code <div>}、中身の {@code <main>} は直下に置く。
     *
     * <p>{@code .container} を挟むと、ブロックの間を決める
     * {@code .theme-desk .staff-main > main} が届きません。
     */
    @Test
    @DisplayName("★ 税理士も .staff-main > main の形（.container を挟まない）")
    void theLedgerLayoutMatchesTheStaffLayout() throws Exception {
        String ledger = Files.readString(LEDGER).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");

        assertThat(ledger)
                .as("器がまだ <main class=\"staff-main\">。main が入れ子になり、"
                        + "HTML として不正なうえ、ブロックの間の規則も届かない")
                .doesNotContain("<main class=\"staff-main\">")
                .contains("<div class=\"staff-main\">");

        assertThat(ledger)
                .as(".container が残っている。あいだに挟まると "
                        + ".theme-desk .staff-main > main（縦オートレイアウト）が効かない")
                .doesNotContain("container container--wide");

        // 店舗管理と同じ形であること
        String staff = Files.readString(STAFF).replace("\r\n", "\n");
        assertThat(staff).as("店舗管理の器が変わっている。どちらかに寄せること")
                .contains("<div class=\"staff-main\">");
    }

    /** ★ 題の行の上下は 16px。ここが 0 だと、上と下が 16px ずつ足りなくなる。 */
    @Test
    @DisplayName("★ 税理士の題の行も上下 16px（店舗管理の見出し帯と同じ）")
    void theLedgerTitleRowHasTheSamePadding() throws Exception {
        assertThat(css())
                .as("税理士の題の行に上下の余白が無い。"
                        + "器の上～題が 16px、題～次が 16px 足りなくなる")
                .contains(".theme-ledger .section-title:has(h1) { padding-block: 16px; }");

        assertThat(css())
                .as("iPad で一段詰める指定が無い（.page-head は 12px に落ちる）")
                .containsPattern("(?s)@media \\(max-width: 1380px\\) \\{\\s*"
                        + "\\.theme-ledger \\.section-title:has\\(h1\\) \\{ padding-block: 12px; \\}");
    }

    /**
     * ★ 入力欄は 16px を割らない。
     *
     * <p>iOS は 16px 未満の入力欄にフォーカスすると画面を勝手に拡大します。
     * 営業中に卓の画面が飛ぶので、幅が足りないときも字で稼がないこと（CLAUDE.md）。
     */
    @Test
    @DisplayName("★ 入力欄の字は 16px を割らない（iOS の拡大よけ）")
    void noInputGoesBelowSixteen() throws Exception {
        String css = css();

        java.util.List<String> small = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([^\\n{}]*\\.input[^\\n{}]*)\\{([^}]*)\\}").matcher(css);
        while (m.find()) {
            java.util.regex.Matcher f = java.util.regex.Pattern
                    .compile("font-size:\\s*(\\d+(?:\\.\\d+)?)px").matcher(m.group(2));
            if (f.find() && Double.parseDouble(f.group(1)) < 16) {
                small.add(m.group(1).trim() + " → " + f.group(1) + "px");
            }
        }
        assertThat(small)
                .as("16px 未満の入力欄がある。iOS がフォーカス時に画面を拡大する")
                .isEmpty();
    }

    /** ★ 節の補足は 16px。画面ごとに 13 / 14 / 16 とばらついていた。 */
    @Test
    @DisplayName("★ 節の補足は 16px（画面ごとに変えない）")
    void theSectionCountIsShared() throws Exception {
        String css = css();

        assertThat(css).as("共通の 16px が無い")
                .contains(".theme-desk .section-title__count { font-size: 16px; }");
        assertThat(css).as("ホールの 13px の上書きが残っている")
                .doesNotContain("h1.section-title__text + .section-title__count { font-size: 13px");
        assertThat(css).as("品切れの 14px の上書きが残っている")
                .doesNotContain(".soldout-head .section-title__count { font-size: 14px");
    }
}
