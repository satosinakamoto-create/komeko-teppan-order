package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注文の「ご要望」自由入力を廃止したことを見張る（2026-09-22）。
 *
 * <p><b>店主の判断は 2 段階でした。</b>
 * <pre>
 *   「お客がわざわざ文字入力するわけないもん。めんどくさがって店員に直接言うでしょ」
 *   「営業中にわざわざテキスト入力するヒマなんてないでしょ」
 * </pre>
 *
 * <p><b>決め手は手間ではなく構造です。</b>
 * 要望は注文ぜんぶに 1 つしか持てません（{@code Order#note}。{@code OrderLine} には無い）。
 * お好み焼きとたこ焼きを一緒に頼んで「ソース多めで」と書かれても、
 * 厨房ボードには<b>どちらへの要望か分からない</b>形でしか出せませんでした。
 * 入力者をお客さまから店員に変えても、この欠陥は消えません。
 * <b>品を選べない入力欄は、書いた人の意図を必ず落とします。</b>
 *
 * <p><b>正しい道は既にあります——商品のオプションです。</b>
 * 「ねぎ増し」「レア寄りで」は品に紐づき、選ぶだけで、
 * 厨房ボードでは品名の直下に出ます（「└ ねぎ増し」）。
 * 同じ目的に 2 つの仕組みを残すと、店員はどちらに入れるか迷い、
 * 次に作る人は両方を考え続けることになります。
 * 食材の分類を 2026-09-16 に廃止したときと同じ判断です
 * （CLAUDE.md「やらないと決めたこと」の「人に入力させる画面をもう一度作らない」）。
 *
 * <p><b>消したのは書き込む口だけです。</b>
 * {@code Order.note} の列も、過去の注文に入っている文字も残しています。
 * 表示（厨房ボード・ホールの伝票・注文履歴）もそのままで、
 * 古い注文を開いたときに要望が消えて見えることはありません。
 */
class CustomerNoteRemovedTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 画面

    /**
     * ★ 入力欄が 1 つも残っていないこと。
     *
     * <p>カートの form は<b>お客さまと店員代行の共用</b>で（{@code th:action} が
     * {@code staffMode} で切り替わる）、ホールの注文追加にも同じ欄がありました。
     * 片方だけ消すと、もう片方から同じ文字が入り続けます。
     */
    @Test
    @DisplayName("★ 注文の要望を入力する欄が、どの画面にも無い")
    void noScreenAsksForAFreeTextNote() throws Exception {
        for (String path : List.of(
                "src/main/resources/templates/customer/cart.html",
                "src/main/resources/templates/hall/order-new.html")) {
            assertThat(read(path))
                    .as("★ " + path + " に要望の入力欄が残っている")
                    .doesNotContain("name=\"note\"");
        }
    }

    /**
     * ★ 「会計メモ」は別物なので消さないこと。
     *
     * <p>{@code hall/bill.html} の {@code name="note"} は
     * <b>伝票のメモ（{@code TableSession#note}）</b>で、注文の要望ではありません。
     * 名前が同じなので、まとめて消してしまう事故が起きやすい場所です。
     * 会計時の申し送り（「領収書は後日」など）はここにしか残せません。
     */
    @Test
    @DisplayName("★ 会計メモ（伝票のメモ）は別物なので残っている")
    void theBillMemoIsUntouched() throws Exception {
        assertThat(read("src/main/resources/templates/hall/bill.html"))
                .as("★ 会計メモまで消えている。これは TableSession#note で注文の要望ではない")
                .contains("会計メモ");
    }

    // ---------------------------------------------------------------- サーバ

    /**
     * ★★ 画面から消すだけでは足りない。
     *
     * <p>注文の口は<b>古い画面を開いたままの端末</b>からも届きます。
     * お客さまの {@code /checkout} は認証なし（{@code SecurityConfig} で permitAll）ですし、
     * 店員のタブレットも開きっぱなしで営業します。
     * 欄を消したあとも {@code note=...} を送ってくる端末はしばらく残るので、
     * <b>受け口そのもの</b>を閉じておきます。
     *
     * <p>「画面に無いから来ない」は Web では成り立ちません。
     * CLAUDE.md の「価格・品切れ・選択肢の妥当性は必ずサーバ側で再検証する」と同じ考え方で、
     * 画面は入力の案内であって、守りではありません。
     */
    @Test
    @DisplayName("★★ 注文を作る 3 つの口が、どれも note を受け取らない")
    void noEndpointAcceptsANote() throws Exception {
        // メソッド名 → そのメソッドの引数に note が無いこと
        record Target(String path, String method) { }
        for (Target t : List.of(
                new Target("src/main/java/jp/komeko/order/web/customer/CheckoutController.java",
                        "public String placeOrder("),
                new Target("src/main/java/jp/komeko/order/web/hall/HallController.java",
                        "public String addOrder("),
                new Target("src/main/java/jp/komeko/order/web/staff/StaffOrderController.java",
                        "public String submit("))) {
            String java = read(t.path());
            int at = java.indexOf(t.method());
            assertThat(at).as(t.method() + " が " + t.path() + " に無い").isGreaterThan(0);
            String args = java.substring(at, java.indexOf(")", at));
            assertThat(args)
                    .as("★ " + t.method() + " がまだ note を受けている。"
                            + "画面から欄を消しても、古い端末から送られた文字が保存される")
                    .doesNotContain("note");
        }
    }

    // ---------------------------------------------------------------- 残す側

    /**
     * ★ 過去の要望は読めること。
     *
     * <p>書き込む口を閉じただけで、データは消していません。
     * 表示まで外すと、廃止前の注文を開いたときに<b>要望が無かったことになります</b>。
     * 何が起きたかの記録は、機能をやめた後も残すのが筋です。
     */
    @Test
    @DisplayName("★ 過去の注文の要望は、いまも画面に出る")
    void oldNotesAreStillShown() throws Exception {
        // ★ 見えている文字で確かめます（2026-09-23）。
        //   以前は Thymeleaf の式（"order.note"）を探していましたが、
        //   厨房ボードを品ごとに作り替えたときに式が ${t.order().note} へ変わり、
        //   <b>表示は残っているのにテストだけ落ちました</b>。
        //   守りたいのは「お客さまに見える形で残っているか」なので、
        //   式ではなくラベルを見ます。
        assertThat(read("src/main/resources/templates/kitchen/board.html"))
                .as("★ 厨房ボードから要望の表示が消えている")
                .contains("ご要望：")
                .contains(".note");
        assertThat(read("src/main/resources/templates/admin/orders.html"))
                .as("★ 注文履歴から要望の表示が消えている")
                .contains("o.note");
    }
}
