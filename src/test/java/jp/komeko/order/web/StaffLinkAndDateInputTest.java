package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * スタッフ側の本文リンクの色と、日付・月の入力欄の幅（2026-09-17、店主の指摘）。
 *
 * <p><b>① 取引先の名前が旧ティールだった。</b>仕入れ・経費の一覧で、
 * 店名のリンクが古い緑のままでした。
 *
 * <p>原因は共通の {@code a { color: var(--accent) }} です。{@code --accent} は
 * {@code :root} では黒ですが、{@code .theme-desk} がティール（{@code --green-700}）に
 * 上書きします。リンクは押せるものなので、草緑（{@code --action}）にそろえます。
 *
 * <p><b>クラスの付いたリンクは巻き込みません。</b>{@code a:not([class])} で
 * 本文のリンクだけを狙います。サイドバーの項目（{@code .sb__item}）やボタン
 * （{@code .btn}）、札（{@code .badge--draft}）はそれぞれ自分の色を持っていて、
 * ここで塗り替えると現在地の表示などが壊れます。
 *
 * <p><b>お客さま側は触りません。</b>あちらの {@code --accent} はきつね色で、
 * 和モダンの骨格そのものです（CLAUDE.md）。{@code .theme-desk} に閉じ込めます。
 *
 * <p><b>② 月の入力欄で「2026年09月」が切れていた。</b>
 * {@code .input--narrow} の {@code max-width: 8rem}（128px）では、
 * 日本語の年月表記とカレンダーのアイコンが入りきりません。
 * 日付・月の行は {@code .datenav} に寄せて、幅もそこで決めます。
 */
@DisplayName("スタッフ側の本文リンクと、日付・月の入力欄")
class StaffLinkAndDateInputTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path PURCHASES =
            Path.of("src/main/resources/templates/inventory/purchases.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    private String rule(String selector) throws Exception {
        String css = css();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{")
                .matcher(css);
        assertThat(m.find()).as("%s が app.css に無い", selector).isTrue();
        return css.substring(m.start(), css.indexOf("}", m.start()));
    }

    // ------------------------------------------------------------------
    // ① リンクの色
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ スタッフ側の本文リンクは草緑")
    void staffBodyLinksUseTheActionGreen() throws Exception {
        String r = rule(".theme-desk a:not([class])");
        assertThat(r).as("本文リンクが草緑になっていない").contains("color: var(--action)");
    }

    /**
     * ★ クラスの付いたリンクを巻き込まないこと。
     *
     * <p>{@code .theme-desk a} と書くと (0,1,1) になり、{@code .sb__item}（0,1,0）に
     * 勝ってしまいます。サイドバーの項目が全部緑になります。
     */
    @Test
    @DisplayName("★ クラス付きのリンク（サイドバー・ボタン）を巻き込まない")
    void itDoesNotTouchClassedLinks() throws Exception {
        String css = css();

        assertThat(css)
                .as(".theme-desk a を無条件で塗ると、サイドバーの項目まで緑になる")
                .doesNotContain("\n.theme-desk a {");
        assertThat(css).as(":not([class]) で本文リンクに絞っていない")
                .contains(".theme-desk a:not([class])");
    }

    /** ★ お客さま側のきつね色は据え置き。共通の a はそのまま。 */
    @Test
    @DisplayName("★ 共通の a は --accent のまま（お客さま側を巻き込まない）")
    void theCustomerSideKeepsItsAccent() throws Exception {
        assertThat(rule("a"))
                .as("共通のリンク色まで変えている。お客さま側のきつね色が消える")
                .contains("color: var(--accent)");
    }

    // ------------------------------------------------------------------
    // ② 月の入力欄の幅
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 月の入力欄は「2026年09月」とカレンダーが切れない幅")
    void theMonthInputIsWideEnough() throws Exception {
        String r = rule(".datenav .input");

        assertThat(r).as("幅の決め方が .datenav に無い").contains("width:");
        assertThat(r)
                .as("最低幅が無い。日本語の年月＋カレンダーのアイコンが入らず切れる")
                .contains("min-width:");
    }

    /** ★ 仕入れ・経費の月の行も、注文履歴・税理士と同じ .datenav に寄せる。 */
    @Test
    @DisplayName("★ 仕入れ・経費の月の行も .datenav を使っている")
    void thePurchasesScreenUsesTheSharedRow() throws Exception {
        String html = Files.readString(PURCHASES).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");

        int at = html.indexOf("type=\"month\"");
        assertThat(at).as("月の入力欄が無い").isGreaterThan(0);

        String form = html.substring(Math.max(0, at - 400), at);
        assertThat(form).as("共通の .datenav を使っていない").contains("datenav");
        assertThat(html.substring(at - 200, at))
                .as(".input--narrow（8rem）が残っていて切れる")
                .doesNotContain("input--narrow");
    }
}
