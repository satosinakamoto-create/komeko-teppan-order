package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「押せるもの」の緑を 1 つの栓で握る（2026-09-16、店主の判断）。
 *
 * <p><b>店主の指摘</b>——「緑が 2 種類ある。昔の緑（ティール）はくすんでいて
 * ボタンを押そうって気がしない」。調べたところ、2 つは新旧ではなく役割違いでした。
 *
 * <pre>
 *   --accent  … #0b7a78 ティール。選択中・現在地・装飾（81 箇所）
 *   --action  … #0b7a1a 草緑。押せるもの（9 箇所だけ）
 * </pre>
 *
 * <p><b>2 つの違いは「青の量」だけです。</b>R=11 G=122 は同じで、青を 120 → 26 に
 * 落としてあります。明度はむしろわずかに暗い（対白 5.16:1 → 5.51:1）。
 * つまり<b>明るくしたのではなく、くすみを取った</b>。
 *
 * <p><b>このテストが守っているのは「ベタ書きを増やさない」ことです。</b>
 * トークンを通らない色は、色を変えたときに必ず取り残されます。
 * 実際 hover の {@code #0a6b17} は 2 か所に重複していて、
 * {@code --action} だけ動かすと<b>hover のほうが明るくなる逆転</b>が
 * 起こりうる状態でした。
 *
 * <p><b>そして最大の罠</b>（{@link #theTokensLiveWhereEveryLayoutCanSeeThem()}）。
 * トークンを {@code .theme-desk} に置くと、ログイン画面（{@code layout/plain}）からは
 * <b>参照できません</b>。実際それでログインボタンの色を一度壊しました。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("押せるものの緑は 1 つの栓で握る")
class ActionGreenTokenTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** コメントを落とした CSS。禁止したい文字列が注意書きに出てくるため。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    // ---------------------------------------------------------------- 置き場所

    /**
     * ★ トークンは 4 レイアウト共通の層（{@code .theme-snow}）に置く。
     *
     * <p>{@code .theme-desk} はスタッフ画面にしか付きません。
     * ログイン画面は {@code layout/plain} なので、そこに置くと参照できず、
     * ベタ書きに戻すしかなくなります。<b>実際に一度そうして壊しました。</b>
     *
     * <p>定義するだけでは何も変わりません（参照した場所だけが緑になる）。
     * お客さん側のボタンは {@code --accent}（黒）のままです。
     */
    @Test
    @DisplayName("★ --action 系は .theme-snow に置く（plain からも参照できる）")
    void theTokensLiveWhereEveryLayoutCanSeeThem() throws Exception {
        String css = css();

        int snow = css.indexOf(".theme-snow {");
        int snowEnd = css.indexOf("\n}", snow);
        assertThat(snow).as(".theme-snow が無い").isGreaterThan(0);

        String snowBlock = css.substring(snow, snowEnd);
        assertThat(snowBlock).as("--action が .theme-snow に無い").contains("--action:");
        assertThat(snowBlock).as("--action-hover が無い").contains("--action-hover:");
        assertThat(snowBlock).as("--action-soft が無い").contains("--action-soft:");
        assertThat(snowBlock).as("--on-action が無い").contains("--on-action:");
    }

    // ---------------------------------------------------------------- ベタ書き禁止

    /**
     * ★ 草緑と hover の実値を、トークンの定義以外に書かないこと。
     *
     * <p>ベタ書きが 1 つでも残ると、色を変えたときにそこだけ取り残されます。
     * そして<b>取り残されたことに誰も気づけません</b>——見比べないと分からないので。
     */
    @Test
    @DisplayName("★ #0b7a1a と #0a6b17 は、定義の 1 箇所以外に書かない")
    void theGreenIsNeverHardcodedOutsideItsDefinition() throws Exception {
        String css = withoutComments(css());

        assertThat(countOf(css, "#0b7a1a"))
                .as("草緑 #0b7a1a がベタ書きされている（--action を使うこと）")
                .isEqualTo(1);
        assertThat(countOf(css, "#0a6b17"))
                .as("hover の #0a6b17 がベタ書きされている（--action-hover を使うこと）")
                .isEqualTo(1);
    }

    private int countOf(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    // ---------------------------------------------------------------- 押せるものの範囲

    /**
     * ★ 机で見る画面のタブ（すべて／掲載中…）は押せるものなので草緑（2026-09-16、店主の指摘）。
     *
     * <p>「商品の すべて・掲載中 とかのボタンが旧色」という指摘から。
     * 実測すると、同じ画面の「＋商品を追加」が {@code #0b7a1a} 草緑なのに、
     * すぐ下のタブは {@code #0b7a78} ティールで、<b>並ぶと色が食い違って</b>いました。
     *
     * <p>タブは当初「選択中＝ティール」の側に分類していましたが、
     * <b>見た目は押せるボタンそのもの</b>なので、押せる側に寄せます。
     *
     * <p>※ 囲いの {@code .theme-desk} を外さないこと。{@code .tab} という名前は
     * お客さまのスマホのメニュー（粉もの／たこ焼き…）でも使われていて、
     * 2026-09-05 に一度それで事故を起こしています。
     */
    @Test
    @DisplayName("★ 机で見る画面のタブは草緑（--action）を参照する")
    void theDeskTabsUseThePressableGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".theme-desk .tab {");
        assertThat(at).as(".theme-desk .tab が無い").isGreaterThan(0);

        // 未選択の文字
        String base = css.substring(at, css.indexOf("}", at));
        assertThat(base).as("未選択のタブの文字がティールのまま").contains("color: var(--action)");

        // 選択中の面
        int active = css.indexOf(".theme-desk .tab.is-active {");
        assertThat(active).as(".theme-desk .tab.is-active が無い").isGreaterThan(0);
        String on = css.substring(active, css.indexOf("}", active));
        assertThat(on).as("選択中のタブの地がティールのまま").contains("background: var(--action)");
        assertThat(on).as("選択中のタブの枠がティールのまま").contains("border-color: var(--action)");
    }

    /**
     * ★ 囲いを外さないこと。
     *
     * <p>{@code .tab} はお客さまのスマホのメニューでも使われています。
     * {@code .theme-desk} を外すと、暗い帯の中に明るい箱が並ぶ形に戻ります
     * （2026-09-05 に実際に起きた事故）。
     */
    @Test
    @DisplayName("★ タブの上書きは .theme-desk で囲ったまま")
    void theTabOverrideStaysScopedToTheDeskTheme() throws Exception {
        String css = withoutComments(css());

        // 素の .tab に --action を書いていないこと（書くとお客さん側へ漏れる）
        int plain = css.indexOf(System.lineSeparator() + ".tab {");
        if (plain < 0) {
            plain = css.indexOf("\n.tab {");
        }
        assertThat(plain).as("素の .tab が無い").isGreaterThan(0);
        String plainBlock = css.substring(plain, css.indexOf("}", plain));
        assertThat(plainBlock).as("素の .tab に --action を書いている（お客さん側へ漏れる）")
                .doesNotContain("--action");
    }

    /**
     * ★ サイドバーの現在地も草緑（2026-09-16、店主の判断）。
     *
     * <p>2 つの緑は明度がほぼ同じ（0.1535 と 0.1406）で、色相だけが違います。
     * 明度が違えば「強い／弱い」の階層になりますが、<b>同じ明度で色相だけ違う 2 色は、
     * 階層ではなく「揃っていない」に見えます</b>。
     *
     * <p><b>意味の区別は形が持っています。</b>主ボタン＝ベタ塗り＋白文字、
     * 枠線ボタン＝白地＋緑枠、現在地＝薄い地＋太字。形がまったく違うので、
     * 色まで分ける必要がありません。
     */
    @Test
    @DisplayName("★ サイドバーの現在地は草緑（--action）を参照する")
    void theSidebarCurrentItemUsesThePressableGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".sb__item.is-active {");
        assertThat(at).as(".sb__item.is-active が無い").isGreaterThan(0);

        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).as("現在地の地がティールのまま").contains("background: var(--action-soft)");
        assertThat(rule).as("現在地の文字がティールのまま").contains("color: var(--action)");
    }

    /**
     * ★ 成功通知の面を緑で塗らないこと（2026-09-16、店主の指摘）。
     *
     * <p>「追加しました とかのポップアップの色が、押せるボタン色と似てる」。
     * 押せるものを 1 つの緑にそろえた結果、<b>面を緑で塗った通知がボタンと
     * 同じ家族に見える</b>ようになっていました。
     *
     * <p>押せないものをボタンと同じ見た目にすると、
     * <b>押して反応がない</b>という体験になります。面はグレーに落とし、
     * 「良い知らせ」は色ではなく ✓ に言わせます。
     */
    @Test
    @DisplayName("★ 成功通知の面は緑で塗らない（ボタンと見分ける）")
    void theSuccessNoticeDoesNotFillItsFaceWithGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".alert--success {");
        assertThat(at).as(".alert--success が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule)
                .as("通知の面が緑のまま（ボタンと同じ家族に見える）")
                .doesNotContain("background: var(--ok-soft)");
        assertThat(rule).as("面がグレーになっていない").contains("background: var(--surface)");
        assertThat(rule).as("本文が緑のまま").contains("color: var(--text)");
        // 「良い知らせ」の合図は残す
        assertThat(rule).as("良い知らせの合図（左の線）が消えている")
                .contains("border-left-color: var(--action)");
        assertThat(css).as("チェック記号が無い").contains(".alert--success::before");
    }

    // ---------------------------------------------------------------- 逆転しない

    /**
     * ★ hover は本体より暗いこと。
     *
     * <p>hover をベタ書きしていると、{@code --action} だけ動かしたときに
     * hover のほうが明るくなる逆転が起こります。押した瞬間に色が浮くので、
     * 「押せていない」ように見えます。
     */
    @Test
    @DisplayName("★ hover は --action より暗い")
    void theHoverIsDarkerThanTheBase() throws Exception {
        String css = css();

        double base = luminanceOf(valueOf(css, "--action:"));
        double hover = luminanceOf(valueOf(css, "--action-hover:"));

        assertThat(hover)
                .as("hover のほうが明るい（押した瞬間に色が浮く）")
                .isLessThan(base);
    }

    /**
     * ★ 白地に緑文字で読めること（.recbtn の枠と文字）。
     *
     * <p>「もっと明るく」の方向へ動かすと、まずここが割れます。
     * WCAG AA は 4.5:1。いまは 5.51:1 で、余裕は大きくありません。
     */
    @Test
    @DisplayName("★ 白地に緑文字で AA（4.5:1）を満たす")
    void theGreenStaysReadableOnWhite() throws Exception {
        double green = luminanceOf(valueOf(css(), "--action:"));
        double white = luminanceOf("#ffffff");
        double ratio = (white + 0.05) / (green + 0.05);

        assertThat(ratio)
                .as("白地に緑文字が AA を割っている（.recbtn の枠と文字が読めない）")
                .isGreaterThanOrEqualTo(4.5);
    }

    private String valueOf(String css, String token) {
        int at = css.indexOf(token);
        assertThat(at).as(token + " が無い").isGreaterThan(0);
        String rest = css.substring(at + token.length(), css.indexOf(";", at)).trim();
        assertThat(rest).as(token + " が実値でない: " + rest).startsWith("#");
        return rest;
    }

    /** WCAG の相対輝度。 */
    private double luminanceOf(String hex) {
        double[] c = {
                Integer.parseInt(hex.substring(1, 3), 16) / 255.0,
                Integer.parseInt(hex.substring(3, 5), 16) / 255.0,
                Integer.parseInt(hex.substring(5, 7), 16) / 255.0,
        };
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
