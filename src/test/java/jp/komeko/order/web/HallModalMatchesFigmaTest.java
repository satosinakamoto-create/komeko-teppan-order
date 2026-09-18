package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ホールのモーダルの寸法を設計にそろえる（2026-09-18）。
 *
 * <p>店主の指摘は「『どちらか選ばないと締められません』と戻る・会計ボタンの間の
 * 余白が実装のほうが狭い」でした。<b>その 1 か所だけの話ではありませんでした。</b>
 *
 * <p>ト02e（725:3837）と突き合わせたところ、狭かったのは
 * <b>箱の gap が 20px（設計 32px）</b>だったためです。この箱は縦積みなので、
 * gap は中のすべての段に効きます。
 *
 * <pre>
 *                        実装      設計
 *   箱の gap              20px     32px   ← 段という段が 12px ずつ狭い
 *   「どちらか…」→ボタン   20px     48px   ＝ 8（親の下pad）+ 32 + 8（footの上pad）
 *   戻るボタン            139×48   115×48
 *   締めるボタン          317×48   345×48
 * </pre>
 *
 * <p><b>ボタンの幅が違ったのは、両方を伸ばしていたからです。</b>
 * 設計は「戻るは 115px で固定、主ボタンが残りを埋める」。
 * 実装は {@code flex: 1 1 0} を両方に掛けていたので、
 * 内容の長さで割った 139 / 317 になっていました。
 *
 * <p>115 + 12（gap）+ 345 = 472 で、箱の中身の幅とぴったり合います。
 *
 * <p><b>この 4 つは 3 枚のモーダルすべてに効きます</b>
 * （卓を選ぶ・ご注文の確認・お会計）。1 か所ずつ直していたら
 * 同じ指摘を 3 回もらうことになっていました。
 */
@DisplayName("ホールのモーダルは設計どおりの寸法")
class HallModalMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** コメントを落とした CSS。注記に同じ数値が出るので、必ずこちらで探すこと。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private String rule(String selector) throws Exception {
        String css = css();
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    /**
     * ★ 箱の段の間は 32px。
     *
     * <p>ここが 20px だと、モーダルの中の段という段が 12px ずつ狭くなります。
     * 店主が気づいたのは最後の 1 か所でしたが、原因は全部これでした。
     */
    @Test
    @DisplayName("★ モーダルの段の間は 32px")
    void theModalStacksWithThirtyTwo() throws Exception {
        // ★ 段を積んでいるのは箱ではなく面（.hallmodal__pane）。
        //   箱の gap を 32px にしても中身は 1 ミリも動きませんでした（実測で発見）。
        //   箱が積んでいるのは「面」で、面の中の段を積むのがこちらです。
        //   両方 32px にしてあります。どちらかだけだと、
        //   1 枚しか面が無い画面／複数ある画面で挙動が分かれます。
        assertThat(rule(".hallmodal__pane {"))
                .as("面の段の間が設計の 32px でない。中のすべての段が同じだけ狭くなる")
                .contains("gap: 32px");
        assertThat(rule(".hallmodal__box {"))
                .as("箱の段の間が 32px でない")
                .contains("gap: 32px");
    }

    /**
     * ★ 戻る・やめるは 115px で固定。
     *
     * <p>両方を伸ばすと、内容の長さで幅が決まってしまいます
     * （「← 戻る」と「お会計（伝票を締める）」で 139 / 317 になっていました）。
     * 設計は 115 ＋ 12 ＋ 345 ＝ 472 で、箱の中身の幅と合わせてあります。
     */
    @Test
    @DisplayName("★ 副ボタンは 115px 固定、主ボタンが残りを埋める")
    void theFooterButtonsFollowTheDesign() throws Exception {
        String sub = rule(".hallmodal__sub");
        assertThat(sub)
                .as("副ボタンが伸びている。内容の長さでボタンの幅が変わってしまう")
                .doesNotContain("flex: 1 1 0")
                .contains("flex: 0 0 115px");

        assertThat(rule(".hallmodal__main"))
                .as("主ボタンが残りを埋めていない")
                .contains("flex: 1");
    }

    /** ★ 箱の余白と枠は設計どおり（40/24・#e3e3e3 1px）。 */
    @Test
    @DisplayName("★ 箱の余白と枠は設計どおり")
    void theBoxPaddingAndBorderMatch() throws Exception {
        String box = rule(".hallmodal__box {");
        assertThat(box).as("上下 40・左右 24 になっていない").contains("padding: 40px 24px");
        assertThat(box).as("枠が #e3e3e3 1px でない").contains("border: 1px solid #e3e3e3");
    }
}
