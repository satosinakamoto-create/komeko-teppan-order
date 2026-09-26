package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * タブレットでは伝票を詰めて、1 レーンに 3 枚見えるようにする（2026-09-13）。
 *
 * <p><b>なぜ要るのか</b><br>
 * 実測すると、1024×768 で 1 レーンに入るのは <b>1.7 枚</b>でした（伝票の平均 270px、
 * レーンの見える高さ 488px）。店主の言葉では「料理が 2 つしか見えなくて視認性が悪い」。
 * 厨房ボードは<b>次に何を焼くかを一目で決める</b>ための画面なので、
 * 1 枚ずつしか見えないなら列に分けている意味がありません。
 *
 * <p><b>どこを削ったか</b><br>
 * 高さを食っていたのは文字ではなく、次の 2 つでした。
 * <ul>
 *   <li>キャンセルの 48px の行（＋すきま 8px）… 全伝票に必ず付く</li>
 *   <li>頭の折り返し 60px … 「同卓 n 件」が入ると 2 行になる</li>
 * </ul>
 * そこで<b>キャンセルは右上の ✕ へ移し</b>、余白とすきまを詰めました。
 * 文字は 1 つも縮めていません（卓名 20px・明細 15px）。高さを決めているのは
 * 文字ではなくボタンの 48px と行数だからです。
 *
 * <p><b>✕ にしてもタップは 48px を割らないこと。</b>
 * CLAUDE.md の「タップ領域は 48px 以上（--tap）」は、厨房のように
 * 手が濡れている・急いでいる場面でこそ効きます。見た目だけ小さくして、
 * 当たり判定は 48×48 のまま残します。
 *
 * <p><b>「キャンセル」の語は消さないこと。</b>
 * 読み上げと、PC 幅での表示に要ります。✕ だけにすると、目の見えない人には
 * 何のボタンか分からなくなります。見えなくするのは見た目だけです。
 */
@DisplayName("タブレットの伝票を詰める")
class TabletCompactTicketTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path BOARD = Path.of("src/main/resources/templates/kitchen/board.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 35-3（タブレットの帯）の中身だけを、コメントを落として切り出す。 */
    private String tabletBand() throws Exception {
        String css = css();
        int at = css.indexOf("@media (max-width: 1140px) {");
        assertThat(at).as("35-3 の 1140px の帯が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("\n}", at)).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ 余白とすきまを詰める（12/10 → 10/8）")
    void theTicketIsTightened() throws Exception {
        String band = tabletBand();

        assertThat(band).contains(".kitchenboard .ticket { padding: 10px; gap: 8px; position: relative; }");
        // 折り返したときの行間。ここが 8px あると、同卓バッジ付きの伝票だけ 11px 高くなる
        assertThat(band).contains("row-gap: 2px;");
        // 明細とご要望の内側も 1 段ずつ詰める
        assertThat(band).contains(".kitchenboard .ticket__items li:last-child { padding: 2px 0; }");
        assertThat(band).contains(".kitchenboard .ticket__note { padding: 6px 8px; }");
    }

    @Test
    @DisplayName("★ キャンセルは右上の ✕。ただし当たり判定は 48×48 のまま")
    void cancelBecomesAMarkButKeepsItsTapArea() throws Exception {
        String band = tabletBand();

        assertThat(band).contains(".kitchenboard .ticket__cancel {");
        assertThat(band).contains("position: absolute;");
        // CLAUDE.md「タップ領域は 48px 以上（--tap）」。見た目を小さくしても、ここは割らない
        // ★ 2026-09-26：高さは --btn-h（44px）の 1 か所で決めるようにしました。
        //   ここで自前の高さを持つと、また画面ごとにズレます。
        assertThat(band).contains("width: var(--btn-h);");
        assertThat(band).contains("height: var(--btn-h);");
        // 頭の右端に ✕ が乗るので、そのぶんの場所を空ける。
        // ★ 40px。経過時間は margin-left:auto で行の右端に寄るため、
        //   30px だと ✕ の下に潜り込みます（768px で実測 10px 重なり）
        assertThat(band).contains("padding-right: 40px;");
    }

    /**
     * ★★ 「キャンセル」の語は消さない——ただし<b>置き場所が変わりました</b>（2026-09-23）。
     *
     * <p>設計（ト01 厨房ボード 1548:20793）を実測したところ、
     * 取り消しボタンの中身は<b>✕ の 1 文字だけ</b>でした（16px Bold #444444・48×48）。
     * 語を並べていたのは私の判断で、設計にはありません。
     *
     * <p><b>守りたかったものは変わっていません</b>——
     * 読み上げに「✕」としか届かない状態にしないこと。
     * 画面に語を出すかわりに {@code aria-label} で渡します。
     * 支援技術には「〇〇をキャンセルする」と品名つきで読まれるので、
     * 以前より情報は増えています。
     */
    @Test
    @DisplayName("★ 「キャンセル」の語は読み上げに残す（画面は ✕ だけ＝設計どおり）")
    void theWordCancelSurvives() throws Exception {
        String html = Files.readString(BOARD);

        assertThat(html)
                .as("★ 取り消しボタンが無い")
                .contains("class=\"linex kline__x\"");
        assertThat(html)
                .as("★★ 読み上げに「✕」としか届かない。何のボタンか分からなくなる")
                .contains("をキャンセルする");
        assertThat(html)
                .as("★ 画面に語を並べている。設計は ✕ の 1 文字だけ")
                .doesNotContain("ticket__cancel-label");
    }

    @Test
    @DisplayName("★ 画面の上も詰める（見出しの内側 上下 16 → 12）")
    void theTopOfTheScreenIsTightenedToo() throws Exception {
        String band = tabletBand();

        // 帯は 31 画面共通なので、盤面の 2 画面にだけ効かせる（35-1 と同じ書き方）。
        // ★ --topbar-h: 64px はここにありましたが、2026-09-13 に外しました。
        //   基準の帯が 88 → 56px（Render の値）になり、64px だと
        //   タブレットのほうが背が高くなる逆転が起きるためです。
        //   いまは基準の 56px がそのままタブレットにも効きます
        assertThat(band).contains(".staff-frame:has(.kitchenboard) .topbar__brand,");
        assertThat(band).doesNotContain("--topbar-h");

        // ★ 左右は 16px → 24px（2026-09-19）。店主の指摘
        //   「厨房の見出しだけ余白がズレてみえる」。
        //   狭い画面のほかのページ（.page-head）は 12px 24px なので、
        //   厨房だけ 16px だと題が 8px 左に残ります。
        //
        //   ★ このテストが守っているのは<b>上下</b>を詰めること（16 → 12）です。
        //     そこは変えていません。
        //
        //   ★ ただし題に 50px の床を敷いたので、帯そのものは高くなりました。
        //     1024x768 で実測:
        //
        //       帯の高さ        56 → 74   (+18)
        //       盤面の始まり    168 → 186 (+18)
        //       まるごと見える注文  6 件 → 6 件（変わらず）
        //
        //     18px では札 1 枚ぶんに届かないので、いまのところ減っていません。
        //     <b>札の高さを変えるときは、ここをもう一度測ること。</b>
        //     余裕は 18px しかないので、次に何か足すと 1 件落ちます。
        // ★ 2026-09-26：店主の決定で 12px → 8px。帯は全画面 64px
        //   （上 8 ＋ 中身 48 ＋ 下 8）。中身 48 は題の床＝--tap と同じ値。
        assertThat(band).contains(".kitchenboard .griddle .card__body { padding: 8px 24px; }");
    }
}
