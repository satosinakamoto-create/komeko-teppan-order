package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厨房ボードの見出しが、ほかの画面と同じ位置に来ること（2026-09-19、店主の指摘）。
 *
 * <p>店主の言葉は「厨房の見出しだけ余白がズレてみえるな確認して」。測ったら本当にズレていました。
 *
 * <pre>
 *   画面            題の x   題の y    見出しの帯の余白
 *   厨房ボード       312      108       20 / 24      ← ここだけ
 *   ホール・会計     328      104       16 / 40
 *   品切れ・残数     328      104       16 / 40
 *   商品            328      104       16 / 40
 *   食材・在庫       328      104       16 / 40
 *   注文履歴         328      104       16 / 40
 *   卓              328      104       16 / 40
 * </pre>
 *
 * <p><b>16px 左・4px 下にいました。</b>画面を行き来すると題が動いて見えます。
 *
 * <h2>実装は悪くありませんでした</h2>
 * <p>設計（ト01）が 20/24 で、実装はそのとおりに写していました。
 * <b>ズレていたのは設計のほうです。</b>同じ Figma ファイルの中で、
 * 同じ幅の画面がちがう値を持っていました。
 *
 * <pre>
 *   1432px   ト01 厨房 20/24  ／ ト02 ホール 20/40 ／ ト03 品切れ 16/40
 *   1920px   トp01 厨房 20/24 ／ トp02 ホール 16/40 ／ トp06 品切れ 0/40
 *   1024px   トi01 厨房 16/24 ／ トi02 ホール 16/24 ／ トi06 品切れ 0/24
 * </pre>
 *
 * <p>左右は 1432 と 1920 で厨房だけ 24 でした（ほかは 40）。
 * 1024 は 24 でそろっていたので触っていません。
 * <b>Figma 側も ト01 / トp01 を 16/40 に直しました</b>（題 x 312→328、412→428）。
 *
 * <h2>2 つ目：題の箱の高さ</h2>
 * <p>ほかの画面の題は {@code min-height: 50px} で、36px の字を 50px の箱の中で
 * 縦中央に置いています。厨房だけ床が無く 36px のまま上に詰まっていたので、
 * 字の中心が 126px（ほかは 129px）で 3px 上にずれていました。
 *
 * <p><b>床は「帯」ではなく「題」に敷きます。</b>
 * 帯に {@code min-height} を書いても {@code box-sizing: border-box} で
 * padding に食われます（CLAUDE.md の罠）。
 */
@DisplayName("厨房の見出しはほかの画面と同じ位置")
class KitchenHeadLinesUpTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** セレクタの宣言ブロックを取り出す（n 個目）。無ければ空文字。 */
    private String block(String selector, int nth) throws Exception {
        String css = css();
        int at = -1;
        for (int i = 0; i <= nth; i++) {
            at = css.indexOf(selector, at + 1);
            if (at < 0) return "";
        }
        int open = css.indexOf('{', at);
        int close = css.indexOf('}', open);
        if (open < 0 || close < 0) return "";
        return css.substring(open + 1, close).replaceAll("\\s+", "");
    }

    /**
     * ★ 見出しの帯の余白は 16px 40px。ほかの画面（.page-head）と同じ。
     *
     * <p>ここが 24px だと題が 16px 左にずれます。
     */
    @Test
    @DisplayName("★ 見出しの帯は 16px 40px（ほかの画面と同じ）")
    void theBandUsesTheSharedPadding() throws Exception {
        String band = block(".kitchenboard .griddle .card__body", 0);

        assertThat(band).as(".kitchenboard .griddle .card__body が無い").isNotEmpty();
        assertThat(band)
                .as("厨房の見出しの帯の余白がほかの画面とちがう。"
                        + "24px だと題が 16px 左、20px だと 4px 下にずれる")
                .contains("padding:16px40px");

        // ほかの画面の基準と同じ値であること。
        // .page-head を変えたのに厨房を忘れる、が起きないように両方を見る
        String pageHead = block(".page-head {", 0);
        assertThat(pageHead).as(".page-head が無い").isNotEmpty();
        assertThat(pageHead)
                .as("基準（.page-head）が 16px 40px でなくなっている。"
                        + "変えるなら厨房の帯も一緒に変えること")
                .contains("padding:16px40px");
    }

    /**
     * ★ 題の箱は 50px。ほかの画面（.page-head__title）と同じ。
     *
     * <p>床が無いと 36px のまま上に詰まり、字の中心が 3px 上にずれます。
     */
    @Test
    @DisplayName("★ 題の箱は 50px（字の中心をほかの画面とそろえるため）")
    void theTitleBoxHasTheSameFloor() throws Exception {
        String css = css().replaceAll("\\s+", "");

        assertThat(css)
                .as("厨房の題に高さの床が無い。36px のまま上に詰まり、"
                        + "字の中心が 126px（ほかは 129px）になって 3px 上にずれる")
                .contains(".kitchenboard.griddleh1{min-height:50px;display:flex;align-items:center;}");

        // 比べる相手も 50px のままであること
        assertThat(css).as("基準（.page-head__title）の 50px が消えている")
                .contains("min-height:50px");
    }

    /**
     * ★ 狭い画面でもそろえる。
     *
     * <p>ここを忘れると、1141〜1380px のあいだだけ厨房が 16/40 のまま残り、
     * ほかの画面（12/24）と 16px ずれます。
     */
    @Test
    @DisplayName("★ 狭い画面でも厨房の帯はほかの画面と同じ左右 24px")
    void theBandFoldsTogetherWithTheOthers() throws Exception {
        String css = css().replaceAll("\\s+", "");

        // .page-head が 12px 24px に畳むところで、厨房も一緒に畳んでいること
        assertThat(css).as("基準の畳み方（.page-head 12px 24px）が無い")
                .contains(".page-head{padding:12px24px;}");
        assertThat(css)
                .as("厨房の帯が一緒に畳まれていない。"
                        + "1141〜1380px のあいだだけ厨房の題が 16px 右に残る")
                .contains(".kitchenboard.griddle.card__body{padding:12px24px;}");
    }
}
