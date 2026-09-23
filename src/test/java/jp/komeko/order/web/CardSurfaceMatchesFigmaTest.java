package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机で読む画面のカードは「白・枠 #e8e8e8・角丸 6」（2026-09-15）。
 *
 * <p><b>きっかけ。</b>店主から「ト04c 食材の詳細（840:9983）の背景が実装と違う」
 * 「ト05 棚卸し（725:4764）も背景色が違う」と指摘がありました。
 *
 * <p><b>原因。</b>{@code .card} の地は {@code var(--surface)} で、
 * {@code .theme-snow} の {@code #f7f9fb}（うすい灰）でした。Figma はどちらの画面も白です。
 * 品切れ・残数だけは {@code .soldoutpage .card} で白に直してあり、
 * そこに「{@code .card} は地が #f7f9fb で枠が透明なので、設計の白・#e8e8e8 の枠・角丸 6 に直す」
 * と理由まで書いてありました。<b>1 画面だけ直して、共通側が取り残されていた</b>のが本当の姿です。
 *
 * <p><b>ボタンの色は変えません。</b>Figma の ト04c は主ボタンが {@code #0b7a78}（ティール）ですが、
 * 実装は {@code #0b7a1a}（草緑）で、これは 2026-09-12 に
 * 「表の見出しの面と押せるボタンが同じティールで見分けが付かない」という理由から
 * <b>意図して色相をずらした</b>ものです（{@code --action}／app.css 30 節）。
 * ト04c のほうが古い値を持ったまま作られたモックなので、Figma 側を直します。
 * ちなみに ト05（725:4764）の主ボタンは {@code #0b7a1a} で、実装と一致しています。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("カードの地は Figma どおり白")
class CardSurfaceMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** Figma の ト04c / ト05 / ト03 に共通するカードの枠。 */
    private static final String FIGMA_CARD_BORDER = "#e8e8e8";

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 注意書きを落とす。<b>doesNotContain は必ずこちらに掛けること。</b> */
    private String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private String ruleOf(String css, String selector) {
        int at = css.indexOf(selector + " {");
        assertThat(at).as(selector + " が app.css に無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    @Test
    @DisplayName("★ カードの地は白（うすい灰 #f7f9fb のままにしない）")
    void theCardSurfaceIsWhite() throws Exception {
        String rule = ruleOf(withoutComments(css()), ".theme-desk .card");
        assertThat(rule).as("カードの地が白になっていない").contains("background: #ffffff;");
    }

    @Test
    @DisplayName("★ カードの枠は #e8e8e8（透明にしない）")
    void theCardBorderMatchesTheMock() throws Exception {
        String rule = ruleOf(withoutComments(css()), ".theme-desk .card");
        assertThat(rule).contains(FIGMA_CARD_BORDER);
    }

    /**
     * 共通側が正しくなったので、品切れ・残数だけの上書きは要らなくなります。
     * 同じ値の二重定義は「片方だけ動く」事故のもとなので置かない
     * （2026-09-13 の {@code .soldoutpage .searchbox}、
     * 2026-09-15 の {@code .soldoutpage .catpick__head} と同じ判断）。
     */
    @Test
    @DisplayName("★ 品切れ画面に同じ値の上書きを残さない")
    void theSoldoutPageDoesNotRepeatIt() throws Exception {
        assertThat(withoutComments(css()))
                .as("共通側と同じ内容の二重定義が残っている")
                .doesNotContain(".soldoutpage .card {");
    }

    /**
     * ボタンの塗りは草緑のまま。ここが変わっていたら、
     * 2026-09-12 の「押せる色と面の色を分ける」判断が崩れています。
     */
    @Test
    @DisplayName("★ 主ボタンは草緑のまま（ティールに戻さない）")
    void thePrimaryButtonKeepsTheActionGreen() throws Exception {
        String css = withoutComments(css());
        assertThat(css).contains("--action:        #0b7a1a;");
        int at = css.indexOf(".theme-desk .btn--primary,");
        assertThat(at).as("主ボタンの定義が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("主ボタンが --action を参照していない")
                .contains("--btn-bg: var(--action);");
    }
}
