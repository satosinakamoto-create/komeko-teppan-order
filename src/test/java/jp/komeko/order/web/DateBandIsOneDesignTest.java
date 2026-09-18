package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日付・月の帯は、どの画面でも同じ形・同じ置き場所にする（2026-09-18、店主の判断）。
 *
 * <p><b>指摘は 2 つでした。</b>
 *
 * <pre>
 *   ①「← 前月 2026年08月 翌月 → の配置場所は見出しの右側、
 *      売り上げの場所を参考に統一させてほしい」
 *   ②「売り上げの ← 前月 2026年08月 翌月 → のデザインは昔のものだから
 *      新しいものに統一させて」
 * </pre>
 *
 * <p><b>置き場所は売上が正しく、見た目は他の画面が正しい</b>という状態でした。
 *
 * <pre>
 *   売上        題の隣にある ○     文字リンク＋ただの文字（昔の形）×
 *   注文履歴    題の下に離れている × ボタン＋入力欄（新しい形）    ○
 *   仕入れ・経費 題の下に離れている × ボタン＋入力欄（新しい形）    ○
 *   税理士 5 枚  題の上に離れている × ボタン＋入力欄（新しい形）    ○
 * </pre>
 *
 * <p>それぞれの良いほうを採って、<b>題の行に・新しい形で</b>そろえます。
 *
 * <p><b>なぜ題の行に置くのか。</b>「いま何月を見ているか」は題とひと続きで読む情報だからです。
 * 離れた位置にあると、題を読んでから視線を下げてもう一度読むことになります。
 * 売上ではこれを 2026-09-13 に店主の指示で直しており、今回はその形に他を合わせます。
 *
 * <p><b>なぜ売上だけ形が違ったのか。</b>売上の帯は設計（17:1038）の「文字リンク式」を
 * そのまま実装したものでした。その後、月を直接選べるようにする必要が出て
 * 他の画面が入力欄つきに変わりましたが、売上だけ取り残されていました。
 */
@DisplayName("日付・月の帯は 1 つの形・1 つの置き場所")
class DateBandIsOneDesignTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /**
     * 帯を直接書いている画面と、その帯が入っているべき「題の行」のクラス。
     *
     * <p>税理士の 5 画面はここに入れません。あちらは共通のフラグメントを呼ぶ形なので、
     * 題の行との関係は {@link #ledgerScreensCallTheSharedBand()} が見ます。
     */
    private static final Map<String, String> SCREENS = Map.of(
            "src/main/resources/templates/admin/sales.html", "page-head",
            "src/main/resources/templates/admin/orders.html", "page-head",
            "src/main/resources/templates/inventory/purchases.html", "page-head");

    /** 税理士の 5 画面。共通のフラグメントを題の行から呼ぶ。 */
    private static final List<String> LEDGER = List.of(
            "src/main/resources/templates/accountant/index.html",
            "src/main/resources/templates/accountant/tax.html",
            "src/main/resources/templates/accountant/evidence.html",
            "src/main/resources/templates/accountant/journal.html",
            "src/main/resources/templates/accountant/rules.html");

    private String read(String p) throws Exception {
        return Files.readString(Path.of(p)).replace("\r\n", "\n");
    }

    private String body(String p) throws Exception {
        return read(p).replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 帯は題の行の中にある。
     *
     * <p>題を開くタグから帯までの間に、行を閉じるタグ（{@code </div>}）が
     * 挟まっていないことで「同じ行の中」と判定します。
     */
    @Test
    @DisplayName("★ 帯は題と同じ行の中にある")
    void theBandSitsOnTheTitleRow() throws Exception {
        for (var e : SCREENS.entrySet()) {
            String html = body(e.getKey());

            int row = html.indexOf("class=\"" + e.getValue());
            assertThat(row).as(e.getKey() + " に題の行（." + e.getValue() + "）が無い").isGreaterThan(0);

            int band = html.indexOf("class=\"datenav", row);
            assertThat(band).as(e.getKey() + " に帯が無い、または題より前にある").isGreaterThan(row);

            assertThat(html.substring(row, band))
                    .as(e.getKey() + " の帯が題の行の外に出ている。"
                            + "題とひと続きで読む情報なので、同じ行に置く")
                    .doesNotContain("</div>");
        }
    }

    /**
     * ★ 売上も新しい形にする。
     *
     * <p>文字リンク式（{@code .monthnav}）は捨てます。月を直接選べないので、
     * いまが 2026-09 で 2025-12 を見たいときに「← 前月」を 9 回押すことになります。
     */
    @Test
    @DisplayName("★ 売上から昔の月ナビ（文字リンク式）が消えている")
    void salesNoLongerUsesTheOldTextNav() throws Exception {
        String html = body("src/main/resources/templates/admin/sales.html");

        assertThat(html)
                .as("売上がまだ昔の月ナビ。月を直接選べず、"
                        + "遠い月へ行くのに前月を何回も押すことになる")
                .doesNotContain("monthnav");
        assertThat(html).as("売上に新しい帯が無い").contains("class=\"datenav");
    }

    /**
     * ★ 売上は期間（1か月〜1年）を持ち回る。
     *
     * <p>GET のフォームは中の入力欄しか送らないので、{@code span} を
     * 隠し入力で持たせないと<b>月を変えるたびに期間が 1 か月へ戻ります</b>。
     */
    @Test
    @DisplayName("★ 売上で月を変えても、選んだ期間が消えない")
    void salesKeepsTheSelectedSpan() throws Exception {
        String html = body("src/main/resources/templates/admin/sales.html");

        int band = html.indexOf("class=\"datenav");
        assertThat(band).as("帯が無い").isGreaterThan(0);
        String form = html.substring(band, html.indexOf("</form>", band));

        assertThat(form)
                .as("span の隠し入力が無い。月を変えるたびに期間が 1 か月へ戻る")
                .contains("type=\"hidden\"").contains("name=\"span\"");
    }

    /** ★ 税理士の 5 画面が、共通のフラグメントを題の行から呼んでいること。 */
    @Test
    @DisplayName("★ 税理士 5 画面が共通の帯を題の行から呼ぶ")
    void ledgerScreensCallTheSharedBand() throws Exception {
        for (String p : LEDGER) {
            String html = body(p);

            int row = html.indexOf("class=\"section-title\"");
            assertThat(row).as(p + " に題の行が無い").isGreaterThan(0);

            int call = html.indexOf("common :: monthband", row);
            assertThat(call).as(p + " が共通の帯を呼んでいない").isGreaterThan(row);
            assertThat(html.substring(row, call))
                    .as(p + " の帯が題の行の外に出ている").doesNotContain("</div>");
        }
    }

    /**
     * ★ 帯はレイアウトから消えていること。
     *
     * <p>題の行へ移したので、レイアウトに残っていると<b>同じ帯が 2 つ</b>出ます。
     */
    @Test
    @DisplayName("★ 税理士レイアウトから帯が消えている（二重表示の防止）")
    void theLedgerLayoutNoLongerHoldsTheBand() throws Exception {
        assertThat(body("src/main/resources/templates/layout/accountant.html"))
                .as("レイアウトに帯が残っている。題の行にも置いたので同じ帯が 2 つ出る")
                .doesNotContain("class=\"datenav");
    }

    /**
     * ★ 帯じたいが並びを決めること。
     *
     * <p>もとは {@code .row .row--wrap} と組にして使っていましたが、題の行（flex）の
     * 中に入れる形になったので、{@code .datenav} だけで完結させます。
     */
    @Test
    @DisplayName("★ .datenav だけで横並びが成立する")
    void theBandIsSelfContained() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        int at = css.indexOf(".datenav {");
        assertThat(at).as("app.css に .datenav の定義が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("横並びになっていない").contains("display: flex");
        assertThat(rule).as("縦位置が揃っていない。入力欄 50px とボタン 40px で段差が出る")
                .contains("align-items: center");
        assertThat(rule).as("部品の間隔が決まっていない").contains("gap:");
    }

    /**
     * ★ 題の行は縦中央で揃えること。
     *
     * <p>{@code .section-title} は既定が {@code align-items: baseline} です。
     * 文字どうしを並べるには正しいのですが、高さ 50px の入力欄が入ると
     * 文字の下端に合わせようとして帯が沈みます。
     */
    @Test
    @DisplayName("★ 帯のある題の行は、縦中央で揃える")
    void theTitleRowCentresWhenItHoldsABand() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        assertThat(css)
                .as("帯を持つ .section-title を縦中央にする指定が無い。"
                        + "baseline のままだと、入力欄が文字の下端に合わせて沈む")
                .containsPattern("\\.section-title:has\\(\\.datenav\\)\\s*\\{[^}]*align-items:\\s*center");
    }
}
