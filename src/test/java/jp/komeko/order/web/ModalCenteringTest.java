package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * モーダルを画面の中央に置く（2026-09-14）。
 *
 * <p><b>何が起きていたか。</b>ホールの「新規お客様」「ご注文の確認」などの
 * モーダルが<b>画面の天井に張り付いて</b>いた。左右は中央なのに上下だけ 0。
 *
 * <p><b>犯人は 41 節。</b>ブロック間を gap に一本化したとき、
 * 子の margin を打ち消す 1 行を入れた。
 * <pre>
 *   .theme-desk .staff-main &gt; main &gt; * { margin-block: 0; }
 * </pre>
 * ホールの {@code <dialog>} は {@code <main>} の直下にいるので、これに当たる。
 * モーダルはブラウザが {@code margin: auto} で中央へ置く決まりなので、
 * <b>上下の margin を 0 にされると中央ぞろえが壊れる</b>
 * （実測：上下 margin 0px・左右 447px ＝ 左右だけ中央）。
 *
 * <p>gap のほうは問題にならない。閉じているモーダルは display:none で、
 * 開いているモーダルは position:fixed で流れから外れるため。
 * 巻き添えなのは margin だけなので、dialog を除くだけで直る。
 *
 * <p>★ 同じ形の事故は「共通の指定で全部そろえる」たびに起きる。
 * 除外を足すのではなく、<b>そもそも何に当たるかを数えてから書くこと</b>。
 */
@DisplayName("モーダルは画面の中央に出す")
class ModalCenteringTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 本文の margin 打ち消しは dialog に当てない（中央ぞろえが壊れる）")
    void theBlockGapResetSkipsDialogs() throws Exception {
        String css = css();
        assertThat(css)
                .as("dialog を除いていない。モーダルが天井に張り付く")
                .contains(".theme-desk .staff-main > main > *:not(dialog) { margin-block: 0; }");
        // 素の形が残っていると、後ろに書かれたほうが勝って元に戻る
        assertThat(css.replaceAll("(?s)/\\*.*?\\*/", ""))
                .doesNotContain(".theme-desk .staff-main > main > * { margin-block: 0; }");
    }

    @Test
    @DisplayName("★ モーダルの中央ぞろえを CSS 側でも押さえる（将来の巻き添え避け）")
    void modalsKeepAutoMargin() throws Exception {
        // ブラウザ既定に任せきりだと、また別の共通指定に巻き込まれる。
        // 明示しておけば、次に誰かが margin を触っても中央が守られる
        assertThat(css()).contains("dialog[open] { margin: auto; }");
    }

    /**
     * モーダルの地は白（Figma ト02b〜02e の「ホール/モーダル」は #ffffff）。
     *
     * <p>{@code var(--surface)} は {@code .theme-snow} で {@code #f7f9fb} ——
     * 薄い青グレーで、暗幕越しに見ると<b>沈んで見える</b>。
     * 白の変数は {@code --bg-elevated}（同テーマで #ffffff）。
     * 名前のとおり「浮いている面」に使うもので、モーダルはまさにそれ。
     */
    @Test
    @DisplayName("★ モーダルの地は白（--surface は薄い青グレーで沈む）")
    void modalBackgroundIsWhite() throws Exception {
        String css = css();
        int at = css.indexOf(".hallmodal {");
        assertThat(at).as(".hallmodal が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("background: var(--bg-elevated);");
        assertThat(rule).doesNotContain("background: var(--surface);");
    }
}
