package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厨房ボードのボタンの高さを 1 か所にそろえる（2026-09-13）。
 *
 * <p><b>なぜ要るのか</b><br>
 * 同じ画面のボタンなのに、高さが 3 通りありました。
 * <ul>
 *   <li>操作（焼きはじめ・焼き上がり・提供済みにする）… 56px（設計 552:6627 の値）</li>
 *   <li>キャンセル … 48px（設計は 40px。タップ規約を優先して上げた、と CSS に記録あり）</li>
 *   <li>タブレット幅の操作 … 48px（35-3 で別に指定していた）</li>
 * </ul>
 * 幅を変えるとボタンの高さが変わる、という状態です。
 * 店主から「ボタン類の高さをそろえるか」と言われたのを機に 48px に一本化しました。
 *
 * <p><b>なぜ 48px か。</b>
 * CLAUDE.md の「タップ領域は 48px 以上（{@code --tap}）」がここの下限だからです。
 * 数値ではなく {@code var(--tap)} で書いているのは、<b>この値が意匠ではなく規約だ</b>と
 * 読んで分かるようにするためです。40px に下げたくなったときは、
 * CSS ではなく CLAUDE.md の側を先に直すことになります。
 *
 * <p><b>40px にしても、ほとんど枚数は増えません。</b>
 * 実測（1 レーンに入る伝票の枚数）:
 * <pre>
 *   iPad 横 1024 … 48px: 2.8 枚 ／ 40px: 2.9 枚
 *   iPad 縦  834 … 48px: 3.1 枚 ／ 40px: 3.3 枚
 *   PC     1432 … 56px: 2.3 枚 ／ 48px: 2.4 枚 ／ 40px: 2.5 枚
 * </pre>
 * 8px 削って増えるのは 0.1〜0.2 枚です。カードの高さを決めているのは
 * ボタンではなく行数（頭の折り返しと明細の数）なので、ここを削っても効きません。
 */
@DisplayName("厨房ボードのボタンの高さ")
class KitchenButtonHeightTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 操作ボタンは var(--tap)。数値で書かない（意匠ではなく規約のため）")
    void actionButtonsUseTheTapVariable() throws Exception {
        String css = css();

        int at = css.indexOf(".kitchenboard .ticket__actions .btn {");
        assertThat(at).as("33-4 の操作ボタンの指定が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).contains("min-height: var(--tap);");
        assertThat(rule).as("56px は設計の値だが、幅で変わる 3 通りの原因だった")
                .doesNotContain("56px");
    }

    @Test
    @DisplayName("★ キャンセルも同じ高さ。ここだけ別の値を持たせない")
    void cancelMatchesTheOthers() throws Exception {
        String css = css();

        int at = css.indexOf(".kitchenboard .ticket__actions .btn--danger {");
        assertThat(at).as("キャンセルの指定が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        // 上の 1 本が var(--tap) を持つので、ここは色だけでよい。
        // 高さを 2 か所に書くと、片方だけ古くなる
        assertThat(rule).doesNotContain("min-height");
        assertThat(rule).contains("--btn-bd: #d33f3f;");
    }

    @Test
    @DisplayName("★ タブレット幅で高さを上書きしない（33-4 の 1 本に任せる）")
    void theTabletBandDoesNotRedeclareTheHeight() throws Exception {
        String css = css();
        int at = css.indexOf("@media (max-width: 1140px) {");
        assertThat(at).as("35-3 の帯が無い").isGreaterThan(0);
        String band = css.substring(at, css.indexOf("\n}", at)).replaceAll("(?s)/\\*.*?\\*/", "");

        // 余白は詰めてよい。高さは触らない
        assertThat(band).contains(".kitchenboard .ticket__actions .btn { padding: 10px 8px; }");

        // ✕ のほうは別物（幅も高さも --tap の正方形）なので、こちらは残る
        assertThat(band).contains(".kitchenboard .ticket__cancel .btn {");
    }
}
