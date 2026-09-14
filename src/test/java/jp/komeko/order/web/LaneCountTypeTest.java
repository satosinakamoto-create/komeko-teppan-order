package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盤面（厨房・ホール）のレーン件数の型（2026-09-14、店主）。
 *
 * <p><b>指示</b>「ト02 ホール会計のフォントサイズの感じを他の iPad とかにも
 * 反映させて。でも背景の灰色は色なしにしてからね」。
 *
 * <p>ホールで作った読みやすさ（数字 20px Bold #1c1c1c）を、
 * 同じレーン形式の厨房ボードにもそろえる。あわせて件数の
 * <b>灰色の器（ピル・丸バッジ）をやめて、数字を裸で置く</b>。
 * 灰色の面があると数字が「部品の中の飾り」に見えて沈む。
 * Figma は 現01・ト01・トi01・トp01（厨房）と現02・ト02・トi02・トp02（ホール）
 * の件数を直し済み。
 *
 * <p><b>対象はレーンの件数だけ。</b>ページ見出しの補足（掲載中 94 品 など）は
 * Figma の部品「見出し/ページ」どおり 14px のまま。
 * お客さま側・スタッフ注文ボードの .lane__head .count（共通 8 節）も触らない。
 */
@DisplayName("レーン件数の型（数字 20px・灰色の器なし）")
class LaneCountTypeTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 宣言ブロックを切り出し、注意書きに一致する罠を避けるためコメントも落とす。 */
    private String block(String selector) throws Exception {
        String css = css();
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at)).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * ★「行を消す」ではなく「none を明示」を見張る。
     *
     * <p>最初は doesNotContain("background") で書いたが、それは誤り。
     * 共通の .lane__head .count（8 節）がピル（--surface-2・角丸・padding）を
     * 持っているので、上書き側から行を消しただけでは<b>基礎のピルが透けて
     * 戻ってくる</b>。器を消すには background: none の明示が要る。
     */
    @Test
    @DisplayName("★ ホールの件数に灰色の器を残さない（none の明示）")
    void theHallCountHasNoPill() throws Exception {
        String b = block(".hallboard .lane__head .count {");
        assertThat(b).contains("background: none;");
        assertThat(b).contains("padding: 0;");
        assertThat(b).contains("border-radius: 0;");
        // 大きさはそのまま（HallStatMergeTest の 20px／16px）
        assertThat(b).contains("font-size: 16px;");
    }

    @Test
    @DisplayName("★ 厨房の件数もホールと同じ型（20px Bold 濃色・器なし）")
    void theKitchenCountMatchesTheHall() throws Exception {
        String b = block(".kitchenboard .lane__head .count {");
        assertThat(b).contains("font-size: 20px;");
        assertThat(b).contains("font-weight: 700;");
        assertThat(b).contains("color: #1c1c1c;");
        assertThat(b).contains("background: none;");
        assertThat(b).contains("padding: 0;");
    }

    @Test
    @DisplayName("★ 共通の .lane__head .count（8 節）は触らない")
    void theSharedCountKeepsItsPill() throws Exception {
        // お客さま側・スタッフ注文ボードのレーンはこの共通版を使っている。
        // 盤面 2 画面の都合で全画面の件数から器を剥がさないこと
        String b = block(".lane__head .count {");
        assertThat(b).contains("background: var(--surface-2);");
    }
}
