package jp.komeko.order.web.hall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ホールのモーダルの色と、消えたボタン（2026-09-17、店主の指摘）。
 *
 * <p><b>① 主ボタンの文字が見えなかった。</b>「次へ」「ご案内する」が白地に白文字で、
 * 空のボタンに見えていました。店主は押せると思わず、別の操作で進んでいます。
 * <b>営業中に使う画面なので、これはいちばん痛い種類の不具合です。</b>
 *
 * <p>原因は詳細度でした。
 *
 * <pre>
 *   .theme-desk .btn   (0,2,0)  --btn-bg: #ffffff       ← 勝つ
 *   .hallmodal__main   (0,1,0)  --btn-bg: var(--action) ← 負ける
 *                               --btn-fg: #fff          ← これは誰も上書きしない
 * </pre>
 *
 * <p>地だけ白に戻され、文字は白のまま残る。だから「白地に白文字」になりました。
 *
 * <p><b>3401 行のコメントが間違っていました。</b>「塗りボタン（.btn--primary /
 * .btn--ok / .btn--danger）は自分で --btn-bg を上書きするので、ここは効きません」と
 * 書いてありますが、それらも (0,1,0) なので同じく負けます。実際には
 * 3573 行の {@code .theme-desk .btn--primary, .theme-desk .btn--ok} が
 * (0,2,0) で救済しているだけで、<b>その救済から漏れたのが .hallmodal__main</b> でした。
 *
 * <p><b>② モーダルが旧ティールのままだった。</b>人数のチップ、卓の行、手順の丸——
 * どれも「選んだもの」＝押せるものなので、草緑（{@code --action}）にそろえます。
 */
@DisplayName("ホールのモーダルの色と主ボタン")
class HallModalColourTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * ★ 白い文字を載せるボタンは、地の指定も勝てる詳細度で書く。
     *
     * <p>{@code --btn-fg} を白にしている規則は、同じ規則（か、それ以上に強い規則）で
     * {@code --btn-bg} も決めていないと、{@code .theme-desk .btn}（0,2,0）に
     * 地だけ奪われて白地に白文字になります。
     *
     * <p>ここではセレクタに<b>クラスが 2 つ以上</b>あることで近似します。
     * {@code .theme-desk .btn} と並んだときに負けない、という意味です。
     */
    @Test
    @DisplayName("★ 白文字のボタンは .theme-desk .btn に負けない詳細度で書かれている")
    void whiteTextButtonsWinTheirBackground() throws Exception {
        String css = css();

        // 「セレクタ { … --btn-fg: #fff … }」を拾う
        Matcher m = Pattern.compile("(?m)^([^{}\\n][^{}]*)\\{([^{}]*)\\}").matcher(css);
        StringBuilder bad = new StringBuilder();

        while (m.find()) {
            String selector = m.group(1).trim();
            String block = m.group(2);
            if (!block.matches("(?s).*--btn-fg:\\s*(#fff|#ffffff|white)\\s*;.*")) {
                continue;
            }
            // .theme-desk 配下で使われない部品（お客さま側・ログイン）は対象外
            if (selector.contains("loginpage") || selector.contains("customer")) {
                continue;
            }
            long classes = selector.chars().filter(c -> c == '.').count();
            if (classes >= 2) {
                continue;   // 自力で勝てる
            }
            // クラス 1 つでも「.theme-desk <その部品>」の救済があれば地は守られる。
            // .btn--ok は 3573 行の .theme-desk .btn--primary, .theme-desk .btn--ok がそれ。
            boolean rescued = false;
            for (String one : selector.split(",")) {
                String s = one.trim();
                if (!s.isEmpty() && css.contains(".theme-desk " + s)) {
                    rescued = true;
                    break;
                }
            }
            if (!rescued) {
                bad.append("\n  ").append(selector)
                        .append("  （クラス ").append(classes).append(" 個・救済も無し）");
            }
        }

        assertThat(bad.toString())
                .as("白文字なのに詳細度が足りない規則がある。"
                        + ".theme-desk .btn（0,2,0）に地だけ奪われて白地に白文字になる:%s", bad)
                .isEmpty();
    }

    /** ★ 3401 行の誤ったコメントを直しておく。次の人が同じ罠にかかる。 */
    @Test
    @DisplayName("★「塗りボタンには効きません」の誤ったコメントが残っていない")
    void theMisleadingCommentIsGone() throws Exception {
        assertThat(css())
                .as("詳細度を誤解させるコメントが残っている")
                .doesNotContain("自分で --btn-bg を上書きするので、ここは効きません");
    }

    // ------------------------------------------------------------------
    // 旧ティール
    // ------------------------------------------------------------------

    /**
     * ★ モーダルの「選んだもの」は草緑。
     *
     * <p>人数のチップ・卓の行・手順の丸は、どれも選択を示す＝押せるものです。
     * 押せるものの緑は {@code --action} に統一しました（2026-09-16〜17）。
     */
    @Test
    @DisplayName("★ 人数のチップ・卓の行・手順の丸が草緑になっている")
    void theModalSelectionsUseTheActionGreen() throws Exception {
        String css = css();

        for (String selector : new String[]{
                ".chip:has(input:checked)",
                ".seatrow:has(input:checked)",
                ".payopt:has(input:checked)",
        }) {
            int at = css.indexOf(selector);
            assertThat(at).as("%s が無い", selector).isGreaterThan(0);
            String rule = css.substring(at, css.indexOf("}", at));
            assertThat(rule).as("%s がまだ旧ティール（--green-700）", selector)
                    .doesNotContain("var(--green-700)");
            assertThat(rule).as("%s が草緑になっていない", selector)
                    .contains("var(--action");
        }

        // 手順の丸（① ②）
        int steps = css.indexOf(".steps2__num.is-current");
        if (steps > 0) {
            String rule = css.substring(steps, css.indexOf("}", steps));
            assertThat(rule).as("手順の丸がまだ旧ティール").doesNotContain("var(--green-700)");
        }
    }
}
