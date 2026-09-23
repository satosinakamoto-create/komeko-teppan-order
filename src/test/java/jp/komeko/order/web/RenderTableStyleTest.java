package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理画面の表を Render のダッシュボードの作りに寄せる（2026-09-13）。
 *
 * <p><b>どこから来た値か</b><br>
 * dashboard.render.com の Ungrouped Services の表を開いて computed style を読んだ値です。
 * <pre>
 *   列見出し   12px / 太さ 400 / 字間 0.24px / 色 #272727 / 面は白
 *   行の罫線   下だけ 1px #e3e3e3（縦罫も縞も無い）
 *   セルの字   16px
 * </pre>
 *
 * <p><b>何を変えたか</b><br>
 * これまでの見出し行は<b>緑のベタ塗りに白 16px 太字</b>でした。
 * 「表の見出しがどこか分からない」という別の問題を直したときの形ですが、
 * 表が 10 画面あると、画面を開くたびに緑の帯が目に入ります。
 * Render は逆に、見出しを<b>いちばん小さく・いちばん静かに</b>して、
 * 中身（店主が読みたい数字と名前）を立たせています。
 *
 * <p><b>行の高さは変えていません。</b>
 * 商品・カテゴリ・仕入れ・食材の表は、それぞれ Figma の設計に
 * 「見出し 16／行 20、高さ 64／68」と書いてあり、値がテストで固定されています。
 * Render は 48px ですが、そちらに寄せると 4 つの設計と、それを守っているテストを
 * 同時に崩すことになります。店主の言葉は「参考にして」だったので、
 * <b>見出しの姿と縞</b>までにとどめました。48px にするかは別の判断です。
 */
@DisplayName("表を Render の作りに寄せる")
class RenderTableStyleTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 列見出しは 12px・太さ 400・字間 0.24px・面は白（緑のベタ塗りをやめる）")
    void theHeaderRowIsQuiet() throws Exception {
        String css = css();

        int at = css.indexOf(".theme-desk .table th {");
        assertThat(at).as("30 節の表の見出しの指定が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).contains("font-size: 12px;");
        assertThat(rule).contains("font-weight: 400;");
        assertThat(rule).contains("letter-spacing: .24px;");
        assertThat(rule).contains("color: #272727;");
        assertThat(rule).contains("background: #ffffff;");

        // 緑のベタ塗りは廃止。白抜きの文字も一緒に消える
        assertThat(rule).doesNotContain("--green-700");
        assertThat(rule).doesNotContain("#ffffff;\n  border-bottom-color");
    }

    @Test
    @DisplayName("★ 罫線は行の下だけ 1px #e3e3e3。縞は引かない")
    void onlyBottomRulesNoZebra() throws Exception {
        String css = css();

        assertThat(css).contains(".theme-desk .table th,\n.theme-desk .table td { border-bottom: 1px solid #e3e3e3; }");

        // 縞（1 行おきの塗り）は Render に無い。行をたどるのは罫線と hover で足りる
        assertThat(css.replaceAll("(?s)/\\*.*?\\*/", ""))
                .as("縞が残っていると、静かにした見出しの意味が消える")
                .doesNotContain(".theme-desk .table tbody tr:nth-child(even)");

        // 指している行が分かる手当ては残す
        assertThat(css).contains(".theme-desk .table tbody tr:hover");
    }

    @Test
    @DisplayName("★ 広い画面で列見出しだけ大きくしない（12px は幅で変えない）")
    void theHeaderDoesNotScaleWithTheViewport() throws Exception {
        String css = css();
        int at = css.indexOf("@media (min-width: 1440px)");
        assertThat(at).as("32 節が無い").isGreaterThan(0);
        String wide = css.substring(at).replaceAll("(?s)/\\*.*?\\*/", "");

        // もとは 17px / 18px に上げていた。12px の見出しをそこまで上げると
        // 中身（16→17→18px）と同じ大きさになり、見出しに見えなくなる
        assertThat(wide).doesNotContain(".table--items th { font-size");
        assertThat(wide).doesNotContain(".table--purchases th { font-size");

        // セルの中身のほうは、幅が広いほど読める情報が増えるので残す
        assertThat(wide).contains(".theme-desk .table      { font-size: 17px; }");
    }
}
