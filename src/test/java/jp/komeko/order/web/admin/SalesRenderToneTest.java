package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 売上画面を設計 14 売上（17:1038）×トンマナ Render に固定する（2026-09-13）。
 *
 * <p><b>レイアウトの正は 17:1038。</b>
 * 中身の並び（月ナビ → 数値 3 枚 → 売上推移＋期間切り替え → 売上の配分の二列 →
 * 注文されている商品）はすでに設計どおり実装されています（alloc の注記参照）。
 * このテストが固定するのは、残っていた 2 つの差です。
 *
 * <ol>
 *   <li><b>見出しの帯</b>。実装は「対象の月」のバーと題「売上」が別々の 2 段でした。
 *       設計は 1 本の帯（16/40）に題と月ナビが同居します（帯 100px）。</li>
 *   <li><b>トンマナ</b>。ダッシュボードと同じ Render（角丸 0・#e3e3e3・影なし・白地）。
 *       .sheet のグレー地（#f7f9fb）は設計に無いので白へ。
 *       売上だけグレーのままだと、隣のダッシュボードと地の色が食い違います。</li>
 * </ol>
 *
 * <p><b>折れ線の点の色の使い分けに注意。</b>
 * ダッシュボード（日次）は金額ラベルが 1 個だけで緑。
 * 売上（月次）は各点にラベルが付き、<b>最後の月だけ</b>緑の太字です（設計どおり）。
 * 共通側（.linechart__plabel + .is-last）が最初からその作りなので、ここでは触らず、
 * 触っていないことをこのコメントで残します。
 */
@DisplayName("売上（設計 17:1038 × トンマナ Render）")
class SalesRenderToneTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path HTML = Path.of("src/main/resources/templates/admin/sales.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    private String block(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    @Test
    @DisplayName("★ 題と月ナビは 1 本の帯（page-head の中に monthnav）")
    void titleAndMonthNavShareOneBand() throws Exception {
        String html = Files.readString(HTML);

        assertThat(html).contains("class=\"sheet salespage\"");
        // 帯は素の .page-head（16/40）。--bare は余白 0 なので設計と別物
        assertThat(html).doesNotContain("page-head--bare");
        // 題が先、月ナビが後ろ（帯の右端）
        int title = html.indexOf("page-head__title\">売上");
        int nav = html.indexOf("class=\"monthnav\"");
        assertThat(title).as("題が無い").isGreaterThan(0);
        assertThat(nav).as("月ナビが無い").isGreaterThan(0);
        assertThat(nav).as("月ナビは題の後ろ（帯の中の右端）に置く").isGreaterThan(title);
    }

    @Test
    @DisplayName("★ 帯の中の月ナビは箱をやめる（バー時代の面・枠・角丸を落とす）")
    void monthNavLosesItsBox() throws Exception {
        String rule = block(css(), ".salespage .monthnav {");
        // 上下 13px は帯を設計の 100px にそろえる詰め物（左右は 0）
        assertThat(rule).contains("padding: 13px 0;");
        assertThat(rule).contains("border: 0;");
        assertThat(rule).contains("background: transparent;");
        assertThat(rule).contains("height: auto;");
    }

    @Test
    @DisplayName("★ 月ナビは題のすぐ隣（左寄せ）。デザインは 14 売上・配置はトi18（2026-09-13 店主指示）")
    void monthNavSitsNextToTheTitle() throws Exception {
        String html = Files.readString(HTML);

        // 「デザインだけ 14 売上、配置をトi18 売上に」という指示。
        //   デザイン＝17:1038 の文字リンク式（対象の月・緑リンク・月 24px 太字）→ 触らない
        //   配置　＝Figma 07 トi18（737:7983）＝題のすぐ隣。右端に置くのをやめる
        // 実装上は spacer（のばす）を月ナビの後ろへ動かすだけ
        int title = html.indexOf("page-head__title\">売上");
        int nav = html.indexOf("class=\"monthnav\"");
        int spacer = html.indexOf("page-head__spacer");
        assertThat(title).isGreaterThan(0);
        assertThat(nav).as("月ナビは題の直後").isGreaterThan(title);
        assertThat(spacer).as("のばすは月ナビの後ろ（月ナビを左に寄せる）").isGreaterThan(nav);

        // 題と月ナビの間はトi18 の 24px（共通の .page-head は 16）
        String css = css();
        assertThat(css).contains(".salespage .page-head { gap: 24px; }");
    }

    @Test
    @DisplayName("★ 地は白、カードとパネルは角丸 0・#e3e3e3・影なし")
    void toneMatchesTheDashboard() throws Exception {
        String css = css();
        assertThat(css).contains(".salespage { background: #ffffff; }");

        String card = block(css, ".salespage .statcard {");
        assertThat(card).contains("border-color: #e3e3e3;");
        assertThat(card).contains("border-radius: 0;");
        assertThat(card).contains("box-shadow: none;");

        String panel = block(css, ".salespage .panel {");
        assertThat(panel).contains("border-color: #e3e3e3;");
        assertThat(panel).contains("border-radius: 0;");
    }

    @Test
    @DisplayName("★ 期間の切り替えも角ばらせる（選択中の緑は残す）")
    void segmentedControlIsSquare() throws Exception {
        String css = css();
        String seg = block(css, ".salespage .segmented {");
        assertThat(seg).contains("border-radius: 0;");
        assertThat(seg).contains("border-color: #e3e3e3;");
        assertThat(css).contains(".salespage .segmented__item { border-radius: 0; }");
    }

    @Test
    @DisplayName("★ 折れ線：目盛り #e3e3e3・点は白抜き・面の塗りなし（設計に無い）")
    void chartMatchesTheDesign() throws Exception {
        String css = css();
        assertThat(css).contains(".salespage .linechart__grid line { stroke: #e3e3e3; }");
        assertThat(css).contains(".salespage .linechart__area { display: none; }");
        assertThat(css).contains(".salespage .linechart__dot { border: 2.5px solid #0b7a78; background: #ffffff; }");
    }

    @Test
    @DisplayName("★ ランキング表はパネルの中の読み物（13px 灰・行の上罫線だけ）")
    void rankingIsQuietLikeTheDashboard() throws Exception {
        String css = css();
        String th = block(css, ".salespage .table--ranking th {");
        assertThat(th).contains("font-size: 13px;");
        assertThat(th).contains("color: #828282;");
        assertThat(th).contains("background: transparent;");
        String td = block(css, ".salespage .table--ranking td {");
        assertThat(td).contains("border-top: 1px solid #e3e3e3;");
    }
}
