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
        assertThat(band).contains("width: var(--tap);");
        assertThat(band).contains("height: var(--tap);");
        // 頭の右端に ✕ が乗るので、そのぶんの場所を空ける。
        // ★ 40px。経過時間は margin-left:auto で行の右端に寄るため、
        //   30px だと ✕ の下に潜り込みます（768px で実測 10px 重なり）
        assertThat(band).contains("padding-right: 40px;");
    }

    @Test
    @DisplayName("★ 「キャンセル」の語は消さない（読み上げと PC 幅のため）")
    void theWordCancelSurvives() throws Exception {
        String html = Files.readString(BOARD);

        assertThat(html).contains("class=\"ticket__cancel-mark\"");
        assertThat(html).contains("class=\"ticket__cancel-label\"");
        assertThat(html).contains("キャンセル");
        // ✕ は飾りなので読み上げから外す
        assertThat(html).contains("aria-hidden=\"true\"");

        String css = css();
        // 見た目だけ消す。display:none にすると読み上げからも消える
        assertThat(css).contains(".kitchenboard .ticket__cancel-label {");
        assertThat(tabletBand()).doesNotContain(".ticket__cancel-label { display: none");
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
        assertThat(band).contains(".kitchenboard .griddle .card__body { padding: 12px 24px; }");
    }
}
