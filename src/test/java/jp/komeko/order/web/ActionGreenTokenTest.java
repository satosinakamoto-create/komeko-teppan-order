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
