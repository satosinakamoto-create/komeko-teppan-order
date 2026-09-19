package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * つまんで動かしているあいだの見え方（2026-09-19、店主の指示
 * 「ドラッグ＆ドロップしてる時の動かしてるアニメーション追加して欲しいかな」）。
 *
 * <h2>入れた動きは 3 つ</h2>
 * <ol>
 *   <li><b>掴んだ行が指に付いてくる。</b>{@code transform: translateY} で位置を追います。
 *       <b>ここに transition を当ててはいけません。</b>指より遅れてついてきて
 *       「引きずっている」感じになります</li>
 *   <li><b>よける行が滑る。</b>FLIP——動かす前の位置を控えておき、動かした後に
 *       「元の位置へ戻す変形」を transition 無しで当て、次の描画で 0 へ戻します。
 *       これをしないと行が瞬間移動します</li>
 *   <li><b>指を離したら滑って収まる。</b>transform を 0 に戻してから送ります。
 *       戻さずに送ると、送信のあいだ行が指の位置に浮いたまま止まって見えます</li>
 * </ol>
 *
 * <h2>動きを減らす設定は尊重する</h2>
 * <p>{@code prefers-reduced-motion} が有効な人には滑らせません。
 * 並べ替えそのものは同じように使えて、演出だけ止まります。
 * JavaScript と CSS の両方で止めています（片方だと取りこぼします）。
 *
 * <h2>ブラウザで実際に動かして確かめたこと</h2>
 * <pre>
 *   並び 1 2 3 4     → つまんで下へ →  2 1 3 4
 *   よけた行 2 に     translateY(67px) / transition=none   ← FLIP の 1 段階目
 *   掴んだ行に        translateY(2px) と is-dragging
 *   指を離したあと     transform が空・transition 160ms・is-landing
 *   送信された値      id=1 before=4（3 の後ろ＝4 の直前）
 * </pre>
 */
@DisplayName("つまんで動かすときの見え方")
class ReorderMotionTest {

    private static final Path JS =
            Path.of("src/main/resources/static/js/items-reorder.js");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String js() throws Exception {
        return Files.readString(JS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\\s+", "");
    }

    /** ★ 掴んだ行が指に付いてくること。 */
    @Test
    @DisplayName("★ 掴んだ行は指に付いてくる")
    void theGrabbedRowFollowsThePointer() throws Exception {
        assertThat(js())
                .as("掴んだ行を動かしていない。指と行が離れて見える")
                .contains("translateY(");

        // 差し込み直したあと、本来の位置を測り直していること。
        // これを忘れると、行が入れ替わるたびに位置が飛びます
        assertThat(js())
                .as("差し込み直したあとに本来の位置を測り直していない。"
                        + "入れ替わるたびに行が飛ぶ")
                .contains("layoutTop(");
    }

    /**
     * ★ 指に付いてくる動きに transition を当てないこと。
     *
     * <p>当てると指より遅れてついてきて「引きずっている」感じになります。
     */
    @Test
    @DisplayName("★ 掴んだ行に transition を当てていない")
    void theGrabbedRowHasNoTransition() throws Exception {
        String css = css();
        int at = css.indexOf(".table--itemstr.is-dragging{");
        assertThat(at).as(".is-dragging の指定が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf('}', at)))
                .as("★ 掴んだ行に transition を当てている。"
                        + "指より遅れてついてきて、引きずっているように見える")
                .doesNotContain("transition");
    }

    /**
     * ★ 掴んだ行が下の行に潜らないこと。
     *
     * <p>表の行は既定では重なり順を持ちません。
     */
    @Test
    @DisplayName("★ 掴んだ行は下の行の上に出る（position と z-index）")
    void theGrabbedRowStaysOnTop() throws Exception {
        String css = css();
        int at = css.indexOf(".table--itemstr.is-dragging{");
        String rule = css.substring(at, css.indexOf('}', at));

        assertThat(rule).as("position: relative が無い。下の行に潜って見える")
                .contains("position:relative");
        assertThat(rule).as("z-index が無い。下の行に潜って見える")
                .contains("z-index:2");
    }

    /** ★ よける行が滑ること（FLIP の 2 段階）。 */
    @Test
    @DisplayName("★ よける行は滑る（瞬間移動しない）")
    void theOtherRowsSlide() throws Exception {
        String js = js();

        assertThat(js).as("動かす前の位置を控えていない。FLIP にならない")
                .contains("getBoundingClientRect().top");
        assertThat(js).as("次の描画で戻す処理が無い。変形が当たったまま止まる")
                .contains("requestAnimationFrame");
        assertThat(js).as("いったん transition を切っていない。"
                        + "元の位置へ戻す変形そのものが滑ってしまう")
                .contains("transition = 'none'");
    }

    /**
     * ★ 指を離したら、本来の位置へ戻してから送ること。
     *
     * <p>戻さずに送ると、送信のあいだ行が指の位置に浮いたまま止まって見えます。
     */
    @Test
    @DisplayName("★ 指を離したら本来の位置へ戻してから送る")
    void itLandsBeforeSaving() throws Exception {
        String js = js();
        int land = js.indexOf("is-landing");
        int save = js.lastIndexOf("save(d.tr.dataset.itemId");
        assertThat(land).as("着地の指定が無い").isGreaterThan(0);
        assertThat(save).as("送信が無い").isGreaterThan(0);
        assertThat(land).as("送ってから戻している。浮いたまま止まって見える").isLessThan(save);
    }

    /**
     * ★ 「動きを減らす」設定を尊重すること。
     *
     * <p>JavaScript と CSS の両方で止めます。片方だと取りこぼします。
     */
    @Test
    @DisplayName("★ 動きを減らす設定では滑らせない（JS と CSS の両方）")
    void reducedMotionIsRespected() throws Exception {
        assertThat(js())
                .as("JavaScript 側で「動きを減らす」設定を見ていない")
                .contains("prefers-reduced-motion");

        assertThat(css())
                .as("CSS 側で「動きを減らす」設定を見ていない")
                .contains("@media(prefers-reduced-motion:reduce)");
    }
}
