package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QR の印刷を設計 ト13b（841:10452）に合わせる（2026-09-20）。
 *
 * <p>設計と実装が「別の紙」を描いていました。設計は 1 枚に 2 卓の簡素な札、
 * 実装は 1 卓 1 枚で手順 ①〜④ まで刷る作りです。店主の判断で<b>実装を設計へ寄せました</b>。
 *
 * <p>ここで守るのは、次の人（と未来の自分）が必ず踏む 2 つの罠です。
 *
 * <ol>
 *   <li><b>単位。</b>設計の A4 は 640x905px で 640px が 210mm ＝ <b>3.048px/mm</b>。
 *       いっぽう CSS の px は印刷時に 1/96 インチ ＝ <b>3.78px/mm</b> に固定されます。
 *       設計の「QR 220px」をそのまま CSS に書くと、紙の上では 72mm ではなく
 *       58mm で出ます。<b>1.24 倍ずれるのに、画面では一切気づけません。</b></li>
 *   <li><b>切り取り線。</b>{@code .theme-snow .print-sheet} が枠線を透明にしていて、
 *       破線は今日までずっと見えていませんでした。あの破線は飾りではなく
 *       「ここで切る」という指示です。消えると、紙のどこを切るか分からなくなります。</li>
 * </ol>
 */
@DisplayName("QR の印刷は設計 ト13b どおり")
class QrPrintMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path TPL = Path.of("src/main/resources/templates/admin/qr-print.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** コメントを落とした本文。「書いてあるのに出ていない」を見抜くため、これで判定する。 */
    private String visibleTemplate() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 札の寸法は mm で書くこと。px で写すと紙の上で 1.24 倍ずれる。
     *
     * <p>設計値 ÷ 3.048 が正しい mm です（QR 220 → 72、札の高さ 412 → 135）。
     */
    @Test
    @DisplayName("★ 札の寸法が mm で書いてある（設計の px を直接書かない）")
    void theSheetIsMeasuredInMillimetres() throws Exception {
        String css = css();

        int at = css.indexOf(".print-sheet__qr");
        assertThat(at).as("QR の指定が無い").isGreaterThan(0);
        String qr = css.substring(at, css.indexOf("}", at));

        assertThat(qr)
                .as("★ QR が mm で書かれていない。設計 220px を CSS の px で写すと "
                        + "紙の上では 58mm になり、設計の 72mm より 1.24 倍小さく刷られる")
                .contains("72mm");
        assertThat(qr)
                .as("★ 設計の px（220px）がそのまま書かれている。mm に直すこと")
                .doesNotContain("220px");

        int box = css.indexOf("\n.print-sheet {");
        assertThat(box).as("札の指定が無い").isGreaterThan(0);
        String sheet = css.substring(box, css.indexOf("}", box));
        assertThat(sheet)
                .as("★ 札の高さが mm で書かれていない（設計 412px ÷ 3.048 = 135mm）")
                .contains("135mm");

        assertThat(css)
                .as("★ 紙の大きさが指定されていない。@page が無いとブラウザ既定の "
                        + "用紙・余白で刷られ、設計の余白（24px/48px）が出ない")
                .contains("@page").contains("size: A4");
    }

    /**
     * ★ 切り取り線を消さないこと。
     *
     * <p>{@code .theme-snow} は「枠線を消して面の色だけで区切る」方針で、
     * カード類の {@code border-color} を透明にします。そこに {@code .print-sheet} が
     * 混ざっていました。画面では他のカードと馴染んで見えるので、
     * <b>誰も間違いだと気づけません</b>。紙に出して初めて分かります。
     */
    @Test
    @DisplayName("★ 切り取り線が消されていない（.theme-snow に print-sheet を混ぜない）")
    void theCutLineSurvivesTheSnowTheme() throws Exception {
        String css = css();

        int at = css.indexOf("\n.print-sheet {");
        String sheet = css.substring(at, css.indexOf("}", at));
        assertThat(sheet).as("札に破線の枠が無い").contains("dashed");

        // .theme-snow の「枠線を透明にする」まとめ書きに print-sheet が入っていないこと
        int snow = css.indexOf(".theme-snow .card,");
        assertThat(snow).as(".theme-snow のカードの指定が見つからない").isGreaterThan(0);
        String block = css.substring(snow, css.indexOf("}", snow));

        assertThat(block)
                .as("★ .theme-snow が print-sheet の枠を透明にしている。"
                        + "破線は飾りではなく切り取り線なので、消すと紙のどこを切るか分からなくなる")
                .doesNotContain("print-sheet");
    }

    /**
     * ★ 1 枚に 2 卓。
     *
     * <p>改ページは {@code nth-child} で数えるので、札を包む {@code .print-sheets} が
     * 要ります。これを外すと、上にある案内や警告まで数に入って
     * <b>改ページの位置がずれます</b>（しかも画面では分かりません）。
     */
    @Test
    @DisplayName("★ 1 枚に 2 卓で改ページされる")
    void twoTablesFitOnOneSheet() throws Exception {
        String css = css();
        String html = visibleTemplate();

        assertThat(html)
                .as("★ 札を包む .print-sheets が無い。nth-child が他の要素まで数えて "
                        + "改ページの位置がずれる")
                .contains("class=\"print-sheets");

        int at = css.indexOf(".print-sheets > .print-sheet:nth-child(2n)");
        assertThat(at)
                .as("★ 2 枚ごとの改ページが無い。1 卓 = 1 枚に戻っている")
                .isGreaterThan(0);

        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("改ページの指定になっていない").contains("page-break-after: always");

        // 最後の札に改ページを付けると白紙が 1 枚増える
        assertThat(css.substring(at, css.indexOf("{", at)))
                .as("★ 最後の札が除かれていない。白紙が 1 枚余計に出る")
                .contains(":not(:last-child)");
    }

    /**
     * ★ 設計に無いものを紙に刷らない。
     *
     * <p>手順 ①〜④ と「ご請求額は…」の段落は、設計の札にありません。
     * 人数の選択はお客さまが注文を始める画面で必ず聞かれるので、
     * 紙から案内が消えてもテーブルチャージの計算には影響しません。
     */
    @Test
    @DisplayName("★ 設計に無い手順の箇条書きを紙に刷らない")
    void theStepListIsNotPrinted() throws Exception {
        String html = visibleTemplate();

        assertThat(html)
                .as("★ 手順の箇条書きが紙に残っている。設計の札は "
                        + "店名・卓名・QR・一行の案内・URL の 5 つだけ")
                .doesNotContain("スマートフォンのカメラで");

        // 設計どおりの 5 つが、設計どおりの順で並んでいること
        int shop  = html.indexOf("print-sheet__shop");
        int table = html.indexOf("print-sheet__table");
        int qr    = html.indexOf("print-sheet__qr");
        int lead  = html.indexOf("print-sheet__lead");
        int url   = html.indexOf("print-sheet__url");

        assertThat(shop).as("店名が無い").isGreaterThan(0);
        assertThat(table).as("卓名が無い").isGreaterThan(shop);
        assertThat(qr).as("QR が卓名より前にある").isGreaterThan(table);
        assertThat(lead).as("案内が QR より前にある").isGreaterThan(qr);
        assertThat(url).as("URL が案内より前にある").isGreaterThan(lead);
    }
}
