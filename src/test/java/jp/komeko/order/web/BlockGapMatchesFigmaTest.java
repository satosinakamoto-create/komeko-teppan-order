package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本文のブロックとブロックの間を Figma どおり 48px にする（2026-09-13）。
 *
 * <p><b>Figma はどうなっているか</b><br>
 * 01 ページの全画面で、本文は<b>縦のオートレイアウト</b>です。
 * 見出し帯・しぼり込み・表・注意書き…… が一定の間隔で積まれていて、
 * その値は 16 画面ぜんぶ 48px。例外は 2 つだけでした。
 * <pre>
 *   厨房・ホール・品切れ  24px … 立って使う 3 画面。伝票を 1 枚でも多く見せる
 *   売上             20px … カードとパネルが多く、48 だと 1 画面に収まらない
 *   税理士 5 枚       48px … 本文の作りが別（layout/accountant）なのでここでは触らない
 * </pre>
 *
 * <p><b>★ 立って使う 3 画面は 01 ページを見ないこと。</b>
 * 01 ページの 03 ホール・04 品切れは 48px で描かれていますが、
 * この 3 枚は 2026-09-12 に「現01 / 現02 / 現03」として作り直してあり、そちらが正です。
 *
 * <p><b>いまのコードはどうだったか</b><br>
 * 1432px で 16 画面を採寸したところ、48px で揃っていたのは
 * ダッシュボード・商品・カテゴリ・スタッフの 4 画面だけでした。
 * 残りは <b>0・4・11・15・16・24・31・32・40・57px</b> がひと画面の中に混在していて、
 * 要素ごとの margin（{@code .mt-6}、{@code .section-title} の margin、
 * {@code p.help} の既定値……）が積み上がった結果でした。
 *
 * <p><b>なぜ gap でやるか</b><br>
 * margin をひとつずつ直すと、新しいブロックを足した人が同じ作業をやり直すことになります。
 * Figma がオートレイアウトで持っている以上、コードもオートレイアウト
 * （flex ＋ gap）で持つのが素直です。
 *
 * <p><b>踏んだ罠</b>
 * <ol>
 *   <li>flex では margin が相殺されないので、子の margin を 0 にしないと
 *       gap に足されて 48＋24 のような値になる</li>
 *   <li>gap は<b>高さ 0 の子にも入る</b>。お知らせの枠
 *       （{@code fragments/common :: flash}）は中身が無くても
 *       {@code <div class="stack-sm">} だけは出るので、
 *       そのままだと全画面の先頭に 48px の空白ができる</li>
 * </ol>
 */
@DisplayName("本文のブロック間は Figma どおり 48px")
class BlockGapMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 41 節だけを切り出す（ファイル全体への contains は別の節に当たるので使わない）。 */
    private String section() throws Exception {
        String css = css();
        int at = css.indexOf("41. 本文の縦の並び");
        assertThat(at).as("41 節が無い").isGreaterThan(0);
        int end = css.indexOf("32. 広い画面", at);
        assertThat(end).as("41 節は 32 節の手前に置くこと").isGreaterThan(at);
        return css.substring(at, end);
    }

    @Test
    @DisplayName("★ 本文は縦に積む器（flex ＋ gap 48px）")
    void theBodyIsAVerticalStack() throws Exception {
        String s = section();
        assertThat(s).contains("flex-direction: column;");
        assertThat(s).contains("gap: var(--block-gap, 48px);");
    }

    /**
     * ★ dialog を除く形に変わった（2026-09-14）。
     *
     * <p>素の {@code > *} で書いたら、{@code <main>} 直下にいるホールの
     * {@code <dialog>} まで巻き添えにして、<b>モーダルが天井に張り付いた</b>。
     * モーダルはブラウザが {@code margin: auto} で中央へ置く決まりなので、
     * 上下の margin を 0 にすると中央ぞろえが壊れる。
     * 経緯は {@code ModalCenteringTest}。
     */
    @Test
    @DisplayName("★ 子の margin は打ち消す（ただし dialog は除く）")
    void childMarginsAreCleared() throws Exception {
        assertThat(section())
                .contains(".theme-desk .staff-main > main > *:not(dialog) { margin-block: 0; }");
    }

    @Test
    @DisplayName("★ 中身の無いお知らせの枠は数えない（先頭に 48px の空白ができる）")
    void theEmptyFlashSlotDoesNotEatAGap() throws Exception {
        // :empty は空白だけの中身をどう扱うかがブラウザで割れる。
        // 「要素の子がひとつも無い」で見るほうが確実
        assertThat(section())
                .contains(".theme-desk .staff-main > main > .stack-sm:not(:has(*)) { display: none; }");
    }

    /**
     * ★ 立って使う 3 画面を 48px にしかけて戻しました（2026-09-13）。
     *
     * <p>01 ページの 02 厨房・03 ホール・04 品切れは 24 / 48 / 48 で描かれていますが、
     * この 3 枚は 2026-09-12 に作り直してあり（現01 552:6627 / 現02 542:3705 /
     * 現03 439:2496）、<b>どれも 24px</b> です。01 ページのほうが古い版でした。
     *
     * <p>48 を採ると 1 画面に入る伝票が 1 枚減ります。
     * 店主から「2 つしか見えなくて視認性が悪い」と言われて詰めた画面なので、
     * ここを広げるのは前の判断を黙って戻すことになります。
     */
    @Test
    @DisplayName("★ 例外は立って使う 3 画面の 24px だけ（売上の 20px は 2026-09-20 に撤去）")
    void theStandingScreensAndSalesDiffer() throws Exception {
        String s = section();
        assertThat(s).contains("main.kitchenboard,");
        assertThat(s).contains("main.hallboard,");
        assertThat(s).contains("main.soldoutpage { --block-gap: 24px; }");

        // ★ 売上の 20px は外しました（2026-09-20）。
        //   8/16/24/32/48 という余白の刻みに無い値で、設計ページ 07 でも
        //   「あき 20」は売上 3 画面にしか存在しない外れ値でした。
        //   立って使う 3 画面と違って、詰めた理由の記録もありません。
        assertThat(s)
                .as("売上の 20px が戻っている。刻みに無い値は使わない")
                .doesNotContain("main.salespage { --block-gap: 20px; }");
    }

    /**
     * ★ この余白は 2 段階で消えました。
     * <pre>
     *   〜09-13  margin-top 56px（数字カードと盤面のあいだ。現02 の 8px の矩形ぶん）
     *   09-13    gap 24 ＋ margin 32 ＝ 56px（本文が縦オートレイアウトになった）
     *   09-14    数字カードの段そのものを畳んだ → margin 0。gap 24 だけ
     * </pre>
     * 畳んだ経緯は {@code HallStatMergeTest} に。Figma 現02 も直し済み。
     */
    @Test
    @DisplayName("★ ホールの盤面の上は gap 24 だけ（数字カードの段は畳んだ）")
    void theHallBoardKeepsItsExtraRoom() throws Exception {
        String css = css();
        int at = css.indexOf(".theme-desk .hallboard .board,");
        assertThat(at).as("ホールの盤面の指定が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf("}", at)).replaceAll("(?s)/\\*.*?\\*/", ""))
                .doesNotContain("margin-top");
    }

    @Test
    @DisplayName("★ iPad は一段詰める（B案のブロック間 48→32。例外の 2 画面は据え置き）")
    void theTabletStepDownKeepsTheExceptions() throws Exception {
        String s = section();
        int band = s.indexOf("@media (max-width: 1380px)");
        assertThat(band).as("41 節に iPad の帯が無い").isGreaterThan(0);
        String b = s.substring(band);
        assertThat(b).contains("--block-gap: 32px;");
        // 24 はここでも指定し直す。しないと基準の 32 に広がってしまう
        assertThat(b).contains("--block-gap: 24px;");
        // 20（売上）は 2026-09-20 に撤去。iPad でも既定の 32 に任せる
        assertThat(b).as("売上の 20px が戻っている").doesNotContain("--block-gap: 20px;");
    }

    @Test
    @DisplayName("★ ブロック間の一段下げは 41 節に一本化（40 節の .dashpage gap は残さない）")
    void theStepDownLivesInOnePlace() throws Exception {
        String css = css().replaceAll("(?s)/\\*.*?\\*/", "");
        int at = css.indexOf("@media (max-width: 1380px)", css.indexOf("40. 端末の段差") > 0 ? 0 : 0);
        // 40 節の帯に .dashpage { gap: ... } が残っていると、
        // 41 節（詳細度 0,2,1）に負けて効かない死んだ 1 行になる
        assertThat(css).doesNotContain(".dashpage { gap: 32px; }");
        assertThat(at).isGreaterThan(0);
    }
}
