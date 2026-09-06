package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 伝票・会計（設計 暗04）の寸法を固定する。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 *
 * <p><b>何を守っているか</b><br>
 * 2026-09-06 に、設計と 4 つずれているのが見つかりました。
 * どれも例外を出さず、画面も壊れません。<b>間の取り方が少しずつ違うだけ</b>なので、
 * 設計と並べないと気づけません。
 *
 * <ul>
 *   <li>明細行の左右が 16px だった（設計は 8px）。品名の始まりと金額の終わりが内側に寄る</li>
 *   <li>明細の締めの線を<b>合計側</b>で引いていた。線が合計にくっつき、
 *       設計にある 32px の間が消えていた</li>
 *   <li>タブ帯から見出しまでが 64px だった（設計は 32px）。
 *       {@code .section-title} 自身の上余白 32 と、画面の上余白が足されていた</li>
 *   <li>見出しの左右インデント 40px が無かった</li>
 * </ul>
 */
@DisplayName("伝票・会計の寸法（暗04）")
class BillLayoutStyleTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    private String rule(String selector) throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at));
    }

    @Test
    @DisplayName("★ 明細行の左右は 8px（設計 暗/伝票の行）")
    void rowPadding() throws Exception {
        assertThat(rule(".bill-row {")).contains("padding: 12px 8px;");
    }

    @Test
    @DisplayName("★ 締めの線は明細が持ち、合計は線を引かない")
    void theClosingLineBelongsToTheLines() throws Exception {
        // 線は「明細の締めくくり」であって「合計の飾り」ではない。
        // 合計側で引くと、線が合計にくっついて 32px の間が消える
        assertThat(rule(".bill-lines .bill-row:last-child"))
                .as("明細の最後に線が無い").contains("border-bottom: 1px solid var(--border)");

        String total = rule(".bill-total {");
        assertThat(total).as("合計が線を引いている。間が消える").doesNotContain("border-top: 1px");
        assertThat(total).as("明細との間が空いていない").contains("margin-top: 32px;");
        assertThat(total).contains("padding: 16px 8px;");
    }

    @Test
    @DisplayName("★ 見出しの上余白を二重に持たない（32px であって 64px ではない）")
    void headingSitsThirtyTwoBelowTheTabs() throws Exception {
        String rule = rule(".bill-page .section-title {");

        // .section-title は自前で上に 32px 持っている。
        // 画面の上余白（16 + 16）と足されて 64px になっていた
        assertThat(rule).as("上余白が二重になる").contains("margin-block-start: 0;");
        assertThat(rule).as("見出しのインデントが無い").contains("padding-inline: 40px;");
    }

    @Test
    @DisplayName("★ 伝票の画面だけ上を 32px 空ける（全画面を下げない）")
    void billPageAddsItsOwnTopSpace() throws Exception {
        String css = Files.readString(APP_CSS);
        assertThat(css).as("伝票の上余白の足し込みが無い")
                .contains(".bill-page { padding-block-start: 16px; }");

        // ★ page__body を 32 にしないこと。上の余白は画面ごとに違う
        //   （暗01 メニュー=16 / 暗03 サービス=24 / 暗04 伝票=32）。
        //   ここを触ると全画面が下がる
        int at = css.indexOf(".theme-night .page__body");
        assertThat(css.substring(at, css.indexOf('}', at)))
                .as("全画面の上余白を動かしている")
                .contains("padding-top: 16px");
    }

    @Test
    @DisplayName("★ 伝票に注文の状態を出さない（設計 暗29）")
    void billShowsNoCookingStatus() throws Exception {
        // もとは品名の右に「受付済み／焼いています」を並べていた。
        // 品名・トッピング・状態の 3 つが横に流れて折り返し、
        // 行が 81px（設計 52px）になり、どこで折り返すかも品名の長さで変わっていた。
        // 伝票は「何を頼んだか」と「いくらか」を読む画面、という切り分け
        for (String page : new String[]{"bill.html", "bill-closed.html"}) {
            String html = Files.readString(
                    Path.of("src/main/resources/templates/customer/" + page));
            assertThat(html).as(page + " に状態が戻っている")
                    .doesNotContain("bill-row__state");
            assertThat(html).as(page + " に状態の色が戻っている")
                    .doesNotContain("status.color");
        }
    }

    @Test
    @DisplayName("★ トッピングは品名の下（承りました画面は括弧付きのまま）")
    void toppingsSitUnderTheNameOnlyOnTheBill() throws Exception {
        String css = Files.readString(APP_CSS);

        // 明細だけ縦並び。.bill-row__note は暗07 とも共有しているので、
        // 全体に効かせると、あちらの「（米粉そば ／ チーズ）」が 2 行目に落ちる
        assertThat(rule(".bill-lines .bill-row__name {"))
                .as("トッピングが品名の横に戻っている").contains("flex-direction: column;");
        assertThat(css).as("行送りが品名と同じ 28px のままで開きすぎる")
                .contains(".bill-lines .bill-row__note");

        // 品名と個数を包む span が無いと、縦並びにした瞬間に 2 つが別の行に分かれる
        assertThat(Files.readString(Path.of("src/main/resources/templates/customer/bill.html")))
                .as("品名と個数を包んでいない").contains("bill-row__title");
    }

    @Test
    @DisplayName("★ タブ帯の左右は 8px（現在地の面が画面の端まで届かない）")
    void tabBarHasSidePadding() throws Exception {
        // 0 にすると、選択中の面（#202020）が画面の端まで届いて
        // 帯そのものと地続きに見え、どれが選ばれているのか分かりにくくなる
        assertThat(rule(".tabbar {"))
                .as("タブ帯の左右余白が設計と違う")
                .contains("env(safe-area-inset-top, 0px)) 8px 0;");
    }

    @Test
    @DisplayName("★ 伝票に「追加でご注文する」と自動更新の但し書きを出さない")
    void billHasNoExtraButtonOrNote() throws Exception {
        String html = Files.readString(
                Path.of("src/main/resources/templates/customer/bill.html"));
        // ★ コメントを外してから見る。外した経緯として文言を書き残してあるので、
        //   そのまま探すと自分の説明文に一致する
        String markup = html.replaceAll("(?s)<!--/\\*.*?\\*/-->", "");

        // タブ帯に「お食事」「ドリンク」が常に出ているので、
        // 同じ場所へ行く口が 2 つある状態だった。
        // 主操作（お会計をお願いする）のすぐ下だと、押し間違いの的も増える
        assertThat(markup).as("追加注文のボタンが戻っている").doesNotContain("追加でご注文する");
        assertThat(markup).as("自動更新の但し書きが戻っている").doesNotContain("画面は自動で更新されます");
    }

    @Test
    @DisplayName("会計済みの伝票も、同じ締めの線を持つ")
    void closedBillUsesTheSameLines() throws Exception {
        // .bill-lines を付け忘れると、合計から線が消えて明細と地続きに見える
        assertThat(Files.readString(
                Path.of("src/main/resources/templates/customer/bill-closed.html")))
                .contains("bill-lines");
        assertThat(Files.readString(
                Path.of("src/main/resources/templates/customer/bill.html")))
                .contains("bill-lines");
    }

    /** 伝票の本文。コメントを外してから調べる（説明文に条件式が出てくるため）。 */
    private String billBody() throws Exception {
        return Files.readString(Path.of("src/main/resources/templates/customer/bill.html"))
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 注文が 0 件でもテーブルチャージの行を出す（設計 暗30）")
    void tableChargeShowsEvenWithNoOrders() throws Exception {
        String html = billBody();

        // ★ ここがこの画面のいちばんの落とし穴。
        //   チャージは「着席した時点で」発生しているので、
        //   注文が無いことを理由に明細を丸ごと隠すと、
        //   理由の書かれていない合計だけが立つ。
        //   2026-09-07 まで実際にそうなっていて、
        //   お客さまには「まだ何も頼んでいないのに ¥900」としか見えなかった。
        int lines = html.indexOf("class=\"bill-lines");
        assertThat(lines).as("明細のかたまりが無い").isGreaterThan(-1);
        String linesTag = html.substring(lines, html.indexOf('>', lines));
        assertThat(linesTag).as("注文が 0 件だと明細ごと消える")
                .doesNotContain("orders.isEmpty()");

        // チャージの行の出る条件は「金額があるか」だけ。注文の有無は関係しない
        assertThat(html).as("チャージの行の条件が変わっている")
                .contains("th:if=\"${bill.tableChargeAmount > 0}\"");
    }

    @Test
    @DisplayName("★ 「お料理・お飲み物」は注文があるときだけ")
    void subtotalRowNeedsOrders() throws Exception {
        // 0 件のときに ¥0 の小計を出すと、頼んでいないものの合計を読ませることになる。
        // 設計 暗30 にもこの行は無い
        String html = billBody();
        int at = html.indexOf("お料理・お飲み物");
        assertThat(at).as("小計の行が無い").isGreaterThan(-1);
        String row = html.substring(html.lastIndexOf("<div class=\"bill-row", at), at);
        assertThat(row).as("注文が 0 件でも ¥0 の小計が出る")
                .contains("th:unless=\"${orders.isEmpty()}\"");
    }

    @Test
    @DisplayName("★ 「まだご注文がありません」は明細の下（上に置くと言った直後に金額が並ぶ）")
    void theEmptyNoticeSitsBelowTheLines() throws Exception {
        String html = billBody();

        int lines = html.indexOf("class=\"bill-lines");
        int notice = html.indexOf("class=\"bill-empty\"");
        int total = html.indexOf("class=\"bill-total\"");

        assertThat(notice).as("空の知らせが無い").isGreaterThan(-1);
        assertThat(lines).as("空の知らせが明細より上にある").isLessThan(notice);
        assertThat(notice).as("空の知らせが合計より下にある").isLessThan(total);
        assertThat(html).as("注文があるときにも出ている")
                .contains("th:if=\"${orders.isEmpty()}\">まだご注文がありません");
    }

    @Test
    @DisplayName("★ 空の知らせの間隔と色（32px ／ かすれ）")
    void theEmptyNoticeStyle() throws Exception {
        String rule = rule(".bill-empty {");

        // 上下 32 は明細・合計・但し書きと同じ刻み。ここだけ違うと縦のリズムが崩れる
        assertThat(rule).as("間隔が伝票の刻みと違う").contains("margin: 32px 0 0;");
        assertThat(rule).contains("text-align: center;");

        // 金額でも品名でもなく「いま中身が無い」という状態の説明なので、
        // 補足（--text-muted）よりさらに弱い色にする
        assertThat(rule).as("補足と同じ強さになっている").contains("color: var(--text-faint);");
    }

    @Test
    @DisplayName("行が 1 本も無いときだけ明細を畳む（締めの線が 1 本浮かないように）")
    void emptyLineBoxCollapses() throws Exception {
        // チャージ 0 円の店で、まだ何も頼んでいないときにだけ起きる。
        // ★ :empty ではなく :not(:has(*))。
        //   :empty は改行や字下げの空白も子として数えるので、
        //   Thymeleaf が残す空白に一致せず効かない
        String css = Files.readString(APP_CSS);
        assertThat(css).as("空の明細が締めの線だけ残す")
                .contains(".bill-lines:not(:has(*)) { display: none; }");
        assertThat(css).as(":empty では Thymeleaf の空白に一致しない")
                .doesNotContain(".bill-lines:empty");
    }
}
