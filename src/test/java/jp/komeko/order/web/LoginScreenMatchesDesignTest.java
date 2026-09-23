package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ログイン画面を設計（ト08 ログイン 725:5301）どおりに保つ。
 *
 * <p><b>このテストが守っているもの＝開店前にいちばん最初に見る画面の見た目。</b>
 *
 * <p>2026-09-23 に実測したところ、4 か所ちがっていました。
 * どれも目では分からない差です。
 *
 * <pre>
 *   画面の地      設計 #ffffff   →  実装 #f7f9fb
 *   カードの角丸  設計 0         →  実装 6
 *   カードの枠    設計 #e3e3e3   →  実装 #e8e8e8
 *   入力欄の枠    設計 #e3e3e3   →  実装 #e8e8e8
 *   補足の字      設計 12px      →  実装 13px
 * </pre>
 *
 * <p><b>角丸 0 は書き間違いではありません。</b>
 * 入力欄とボタンが 8 で、外側のカードだけが 0 です。
 * 「カードだから丸める」と思い込むと 6px になります（実際そうなっていました）。
 *
 * <p><b>地の白も、名前で選ぶと外します。</b>
 * {@code #f7f9fb} は {@code --surface} の値で、いかにも背景に見えますが、
 * 設計は素の白です。地が白になると、白いカードを分けているのは
 * 1px の枠だけになるので、枠の色を薄くしすぎないこと。
 *
 * <p>一方で<b>直さなかったもの</b>も書き残します。
 * <ul>
 *   <li>出口リンクの色 … 設計 {@code #0b7a1a}。実装も {@code var(--action)} で同じでした。
 *       親の {@code <p>} を測って「黒い」と誤判定しかけました</li>
 *   <li>ユーザー名欄の枠が緑がかって見える … {@code autofocus} で
 *       フォーカス中だからです。設計はフォーカスしていない状態の絵なので、
 *       パスワード欄で比べるのが正しい</li>
 * </ul>
 */
@DisplayName("ログイン画面を設計どおりに保つ")
class LoginScreenMatchesDesignTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 画面の地は素の白（--surface の #f7f9fb ではない）")
    void theBackgroundIsPlainWhite() throws Exception {
        assertThat(css())
                .as("★ 地が設計と違う。#f7f9fb は --surface の値で、設計は素の白")
                .contains(".loginpage { background: #ffffff; }");
    }

    @Test
    @DisplayName("★★ カードの角丸は 0。入力欄の 8 と取り違えない")
    void theCardHasSquareCorners() throws Exception {
        String css = css();
        int at = css.indexOf(".loginpage .loginpage__card {");
        assertThat(at).as("★ カードの規則が無い").isGreaterThan(0);

        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule)
                .as("★★ カードを丸めている。設計は角丸 0（丸いのは入力欄とボタンだけ）")
                .contains("border-radius: 0;");
        assertThat(rule)
                .as("★ カードの枠が設計と違う")
                .contains("border: 1px solid #e3e3e3;");
    }

    @Test
    @DisplayName("★ 枠の色は #e3e3e3 でそろえる（#e8e8e8 を残さない）")
    void everyBorderUsesTheSameGrey() throws Exception {
        String css = css();
        int from = css.indexOf(".loginpage { background:");
        int to = css.indexOf("/* ====", from);
        assertThat(from).isGreaterThan(0);

        String block = css.substring(from, to > from ? to : css.length())
                .replaceAll("(?s)/\\*.*?\\*/", "");

        assertThat(block)
                .as("★ #e8e8e8 が残っている。設計の枠は全部 #e3e3e3")
                .doesNotContain("#e8e8e8");
    }

    @Test
    @DisplayName("★ 補足は 12px（入力欄16・ボタン15 より一段下げる）")
    void theNoteIsTheSmallestText() throws Exception {
        String css = css();
        int at = css.indexOf(".loginpage__note {");
        assertThat(at).as("★ 補足の規則が無い").isGreaterThan(0);

        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("★ 補足の字が設計と違う")
                .contains("font-size: 12px;");
    }
}
