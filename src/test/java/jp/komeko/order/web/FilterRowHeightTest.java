package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * しぼり込みの 2 つの箱は同じ高さ（設計 ト07 食材・在庫 725:4468・2026-09-14）。
 *
 * <p><b>Figma の実測</b>
 * <pre>
 *   しぼり込みの行 725:4468 … 1096 × 48
 *     「食材を探す」        725:4469 … 527 × 48
 *     「カテゴリーから検索」 725:4474 … 527 × 48   ← 間は 42px
 * </pre>
 *
 * <p><b>なぜ落ちていたか（2026-09-14 に店主が発見）。</b>
 * 探す欄は 2026-09-13 に 64px → 48px へ統一しました（画面ごとに 2 種類あったため）。
 * ところが<b>カテゴリー側は 62px のまま取り残され</b>、横に並ぶ 2 つの箱に
 * 14px の段差ができていました。
 *
 * <p>取り残された理由は CSS のコメントに残っています——
 * 「探す欄（64px）と高さをそろえる」。<b>そろえる相手が動いたのに、
 * 追いかける側は数字を直書きしていた</b>ので気づけませんでした。
 * このテストは「2 つが同じ値であること」を見ます。
 * 片方を動かしたらもう片方も動かす、を人の記憶に頼らないためです。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("しぼり込みの 2 つの箱は同じ高さ")
class FilterRowHeightTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** Figma のしぼり込みの箱（725:4469 / 725:4474・ト03 は 725:4181 / 725:4186）の外形。 */
    private static final int FIGMA_BOX_HEIGHT = 48;

    /** {@code .catpick} の上下の枠。{@code box-sizing:border-box} でも<b>親の枠は別</b>。 */
    private static final int CATPICK_BORDERS = 2;

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * {@code セレクタ { … プロパティ: 値px … }} から数値だけ取り出す。
     *
     * <p>★ 同じセレクタの定義が<b>複数ある</b>ので、最初の 1 つで決め打ちしないこと。
     * {@code .searchbox} は上のほうに {@code margin-top} だけの定義があり、
     * 最初にこれを書いたときは「height が無い」で落ちました。
     * そのプロパティを実際に持っている定義に当たるまで探します。
     */
    private int pixelsOf(String css, String selector, String property) {
        Pattern value = Pattern.compile(property + ":\\s*(\\d+)px");
        int from = 0;
        boolean seen = false;
        while (true) {
            int at = css.indexOf(selector + " {", from);
            if (at < 0) {
                break;
            }
            seen = true;
            Matcher m = value.matcher(css.substring(at, css.indexOf("}", at)));
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
            from = at + 1;
        }
        throw new AssertionError(seen
                ? selector + " のどの定義にも " + property + " が無い"
                : selector + " が app.css に無い");
    }

    @Test
    @DisplayName("★ 探す欄は Figma どおり 48px")
    void theSearchBoxIsFortyEight() throws Exception {
        assertThat(pixelsOf(css(), ".searchbox", "height")).isEqualTo(FIGMA_BOX_HEIGHT);
    }

    /**
     * ★ ここは 2026-09-14 に 1 度まちがえて直しました。記録として残します。
     *
     * <p>最初「探す欄が 48 なのだからカテゴリーも 48」と書いて {@code .catpick__head}
     * を 48px にしました。<b>2px ずれます。</b>
     *
     * <pre>
     *   .searchbox     … height:48px。自分に枠があり box-sizing:border-box なので外形 48
     *   .catpick       … 枠 1px。高さ指定は無く、中の .catpick__head で決まる
     *   .catpick__head … min-height:N。外形は 1 + N + 1
     * </pre>
     *
     * <p>つまり<b>そろえる相手は外形</b>で、{@code N = 48 - 枠 2 = 46}。
     * 品切れ・残数の画面（{@code .soldoutpage}）には最初から 46px の上書きがあり、
     * そこには「枠 1px ＋ 46px ＝ 48px」と正しい理由が書いてありました。
     * 共通側だけが取り残されていた、というのが本当の姿です。
     */
    @Test
    @DisplayName("★ カテゴリーの外形も 48px（枠 1px × 2 を含めて計算する）")
    void theCategoryPickerMatchesItOnTheOutside() throws Exception {
        assertThat(pixelsOf(css(), ".catpick__head", "min-height") + CATPICK_BORDERS)
                .as("枠を足した外形が探す欄と違う（横に並ぶので段差になる）")
                .isEqualTo(FIGMA_BOX_HEIGHT);
    }

    /**
     * ★ 本題。片方だけ動かせないことを、2 つを突き合わせて見る。
     *
     * <p>上の 2 本は「48」という数字そのものを見ているので、
     * 設計が変わって 48 が別の値になったとき、片方だけ直しても通ってしまいます。
     * ここは<b>外形どうしが等しいか</b>を見るので、そのときも落ちます。
     */
    @Test
    @DisplayName("★ 2 つの外形は互いに等しい（片方だけ動かせない）")
    void theTwoBoxesStayInStep() throws Exception {
        String css = css();
        assertThat(pixelsOf(css, ".catpick__head", "min-height") + CATPICK_BORDERS)
                .as("探す欄とカテゴリーの外形がずれている")
                .isEqualTo(pixelsOf(css, ".searchbox", "height"));
    }

    /**
     * 品切れ・残数だけに残っていた上書きは、共通側が正しくなったので不要になりました。
     * 同じ値の二重定義は「片方だけ動く」事故のもとなので置かない
     * （2026-09-13 に {@code .soldoutpage .searchbox} を外したときと同じ判断）。
     */
    @Test
    @DisplayName("★ 品切れ画面に同じ値の上書きを残さない")
    void theSoldoutPageDoesNotRepeatTheSameNumber() throws Exception {
        // ★ 注意書きを落としてから見ること。
        //   このプロジェクトは「外した理由」をコメントに残す方針なので、
        //   外した当の宣言がそのまま注意書きの中に出てきて一致します。
        //   最初これで落ちました（PageHeadMatchesFigmaTest が踏んだのと同じ罠）。
        String withoutComments = css().replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(withoutComments)
                .as("共通側と同じ値の二重定義が残っている")
                .doesNotContain(".soldoutpage .catpick__head { min-height:");
    }
}
