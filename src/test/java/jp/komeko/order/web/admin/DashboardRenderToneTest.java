package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ダッシュボードのトンマナを Render に固定する（2026-09-13）。
 *
 * <p><b>位置づけ</b><br>
 * レイアウトの正は設計 01 ダッシュボード（15:319）で、実装（38 節）は
 * すでにその寸法に乗っています（見出しの帯 16/40・カード 20/gap8・列 gap20・
 * パネル 20/gap12・ランキングは見出し 13px 灰／行 48px 上罫線のみ）。
 * ここで足すのは店主承認のトンマナ（Figma 07 ページ）だけです：
 * <b>角丸 0・薄い枠と罫は #e3e3e3・影なし</b>。
 *
 * <p><b>#e8e8e8 と #e3e3e3 を混ぜないこと。</b>
 * 表（30 節）はすでに #e3e3e3 です。ダッシュボードだけ #e8e8e8 のままだと、
 * 同じ画面幅で並べたときに罫の濃さが 2 種類になります。5px の違いですが、
 * 白地では並べると分かります。
 *
 * <p><b>設計から意図して外しているもの（トンマナ側を優先）</b>
 * <ul>
 *   <li>パネルの題 17px → 20px（Render の節見出し。RenderAlignedTypeTest が固定）</li>
 *   <li>カードの角丸 10px → 0（07 のトンマナ決定）</li>
 * </ul>
 */
@DisplayName("ダッシュボードのトンマナ（Render）")
class DashboardRenderToneTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    private String block(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    @Test
    @DisplayName("★ 数字カード：角丸 0・枠 #e3e3e3・影なし")
    void statcardIsSquare() throws Exception {
        String rule = block(css(), ".dashpage .statcard {");
        assertThat(rule).contains("border-color: #e3e3e3;");
        assertThat(rule).contains("border-radius: 0;");
        assertThat(rule).contains("box-shadow: none;");
    }

    @Test
    @DisplayName("★ パネル（折れ線・ランキング）：角丸 0・枠 #e3e3e3")
    void panelIsSquare() throws Exception {
        String rule = block(css(), ".dashpage .panel {");
        assertThat(rule).contains("border-color: #e3e3e3;");
        assertThat(rule).contains("border-radius: 0;");
        assertThat(rule).contains("box-shadow: none;");
    }

    @Test
    @DisplayName("★ 罫線も #e3e3e3（目盛り・ランキングの行の上罫線）")
    void rulesUseTheSameGray() throws Exception {
        String css = css();
        assertThat(css).contains(".dashpage .linechart__grid line { stroke: #e3e3e3; }");
        assertThat(block(css, ".dashpage .table--ranking td {"))
                .contains("border-top: 1px solid #e3e3e3;");
    }

    @Test
    @DisplayName("★ レイアウトは 15:319 のまま（トンマナ変更で寸法を動かさない）")
    void layoutStaysOnTheDesign() throws Exception {
        String css = css();
        // 見出しの帯 16/40、カードの列 gap 20、ランキングの見出し 13px 灰
        assertThat(block(css, ".page-head {")).contains("padding: 16px 40px;");
        assertThat(css).contains(".statrow { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }");
        String th = block(css, ".dashpage .table--ranking th {");
        assertThat(th).contains("font-size: 13px;");
        assertThat(th).contains("color: #828282;");
    }
}
