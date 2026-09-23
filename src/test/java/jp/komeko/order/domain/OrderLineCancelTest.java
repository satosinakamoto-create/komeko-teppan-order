package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品を 1 つだけ取り消す（2026-09-22 追加）。
 *
 * <p><b>なぜ注文ごとではなく品ごとなのか。</b>
 * カートを一度に確定すると、4 品でも 1 件の {@link Order} になります。
 * 「1 品だけ違うものが来た」ときに注文ごと消すと、
 * 関係のない 3 品まで請求から落ちていました。店主の指摘そのものです——
 * <pre>
 *   「取り消すボタンが卓ごとだから、一個の商品だけ破棄の場合とかだとムリじゃん」
 * </pre>
 *
 * <p><b>このテストが守っているのは金額の連鎖です。</b>
 * <pre>
 *   OrderLine.canceled → Order.recalculate() → TableSession.recalculate() → ご請求額
 * </pre>
 * どこか 1 段でも取り消しを無視すると、
 * <b>「明細は取り消し済みなのに合計は元のまま」</b>という、
 * 誰にも説明できない伝票ができます。2026-08-22 のレビューで
 * 会計済み伝票に同じ形の穴が見つかっており、同じ穴を品ごとの取り消しで開け直さないために置きます。
 *
 * <p><b>行は消しません。</b>「取り消した」と印を付けるだけです。
 * 注文履歴と提供時間の集計は事実の記録なので、消すと
 * 「厨房は作ったのに伝票に無い」を後から説明できなくなります。
 */
@DisplayName("品ごとの取り消し")
class OrderLineCancelTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 22);

    /** 税率 10%・注文番号 101 の伝票に、指定した単価の品を並べる。 */
    private Order orderWith(int... prices) {
        Order order = new Order(BUSINESS_DATE, 101, 10);
        for (int i = 0; i < prices.length; i++) {
            order.addLine(new OrderLine((long) (i + 1), "品" + (i + 1), prices[i], 1, 5));
        }
        order.recalculate();
        return order;
    }

    private OrderLine lineAt(Order order, int index) {
        return order.getLines().get(index);
    }

    @Nested
    @DisplayName("金額")
    class Money {

        /**
         * ★★ これがこの機能の核心。
         *
         * <p>1 品だけ取り消したときに、<b>その品のぶんだけ</b>減ること。
         * 注文ごと取り消していた頃は 3 品とも落ちていました。
         */
        @Test
        @DisplayName("★★ 1 品取り消すと、その品のぶんだけ合計が減る（残りは請求に残る）")
        void cancellingOneLineRemovesOnlyThatLine() {
            Order order = orderWith(880, 2400, 1100);
            assertThat(order.getTotalAmount()).isEqualTo(4380);

            lineAt(order, 1).cancel("お客さま都合", false);
            order.recalculate();

            assertThat(order.getTotalAmount())
                    .as("★ 取り消した 2,400 円だけが引かれること。"
                            + "注文ごと落ちると 0 円になり、残り 2 品を請求できなくなる")
                    .isEqualTo(880 + 1100);
        }

        /** 内税も一緒に付いてくること。合計だけ直して税額を忘れると、レシートの内訳が合わなくなる。 */
        @Test
        @DisplayName("内税も取り消し後の合計から計算し直される")
        void taxFollowsTheNewTotal() {
            Order order = orderWith(880, 2400);
            lineAt(order, 1).cancel("廃棄", false);
            order.recalculate();

            assertThat(order.getTaxAmount())
                    .isEqualTo(TaxCalculator.includedTax(880, 10));
        }

        /**
         * 厨房の見込み時間からも外すこと。
         *
         * <p>取り消した品の調理時間が残っていると、厨房ボードの待ち時間が
         * いつまでも長いままになり、10 分の赤い帯が実態と合わなくなります。
         */
        @Test
        @DisplayName("調理の見込み時間からも外れる")
        void cookTimeDropsToo() {
            Order order = orderWith(880, 2400);
            int before = order.getEstimatedCookMinutes();
            lineAt(order, 1).cancel("廃棄", false);
            order.recalculate();

            assertThat(order.getEstimatedCookMinutes()).isLessThan(before);
        }
    }

    @Nested
    @DisplayName("記録")
    class Record {

        /**
         * ★ 行は消さない。
         *
         * <p>店主の言葉は「削除」でしたが、実際に消してよいのは
         * <b>お会計への計上だけ</b>です。行を消すと、注文履歴からも
         * 提供時間の集計からも消え、「厨房は作ったのに伝票に無い」を
         * 後から説明できなくなります。
         */
        @Test
        @DisplayName("★ 取り消しても行そのものは残る（履歴のため）")
        void theLineItselfSurvives() {
            Order order = orderWith(880, 2400);
            lineAt(order, 1).cancel("お客さま都合", false);

            assertThat(order.getLines()).hasSize(2);
            assertThat(order.getActiveLines())
                    .as("画面に並ぶのは取り消していない品だけ")
                    .hasSize(1);
            assertThat(lineAt(order, 1).getCanceledAt()).isNotNull();
            assertThat(lineAt(order, 1).getCanceledReason()).isEqualTo("お客さま都合");
        }

        /**
         * ★ 在庫を戻したかどうかを残す。
         *
         * <p>「まだ作っていない（戻す）」と「作った・出した（廃棄）」は
         * 材料の行方が違います。どちらを選んだか残しておかないと、
         * あとで理論原価と実際原価が合わない理由を追えません。
         */
        @Test
        @DisplayName("★ 在庫を戻したかどうかが残る（廃棄の原価を説明するため）")
        void remembersWhetherStockWentBack() {
            Order discarded = orderWith(2400);
            lineAt(discarded, 0).cancel("廃棄", false);
            assertThat(lineAt(discarded, 0).isStockReturned()).isFalse();

            Order notMadeYet = orderWith(2400);
            lineAt(notMadeYet, 0).cancel("誤入力", true);
            assertThat(lineAt(notMadeYet, 0).isStockReturned()).isTrue();
        }

        /**
         * ★★ 二度押しで在庫を二重に戻さないこと。
         *
         * <p>古いタブからの再送信や、通信が遅いときの連打で起きます。
         * 二重に戻すと<b>実際には無い残数が画面に出て売り越します</b>。
         * 呼び出し側（{@code OrderService#cancelLine}）はこの戻り値を見て
         * 在庫を戻すかどうかを決めているので、ここが true を返し続けると
         * そのまま在庫が増え続けます。
         */
        @Test
        @DisplayName("★★ 2 回取り消しても「初めて」は 1 回だけ（在庫の二重返却を防ぐ）")
        void cancellingTwiceReportsFirstTimeOnlyOnce() {
            Order order = orderWith(2400);
            OrderLine line = lineAt(order, 0);

            assertThat(line.cancel("廃棄", false)).isTrue();
            assertThat(line.cancel("廃棄", true))
                    .as("★ 2 回目は false。true を返すと在庫を二重に戻す")
                    .isFalse();
            assertThat(line.isStockReturned())
                    .as("★ 1 回目の記録を上書きしないこと。あとから静かに書き換わると原価の説明がつかない")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("全部取り消したとき")
    class Everything {

        /**
         * 品が 1 つも残らない注文は、厨房ボードに出しても焼くものがありません。
         * 呼び出し側がこれを見て注文そのものも CANCELED にします。
         */
        @Test
        @DisplayName("品を全部取り消すと isFullyCanceled が true になる")
        void tellsWhenNothingIsLeft() {
            Order order = orderWith(880, 2400);
            assertThat(order.isFullyCanceled()).isFalse();

            lineAt(order, 0).cancel("廃棄", false);
            assertThat(order.isFullyCanceled())
                    .as("1 品残っているうちは false")
                    .isFalse();

            lineAt(order, 1).cancel("廃棄", false);
            assertThat(order.isFullyCanceled()).isTrue();

            order.recalculate();
            assertThat(order.getTotalAmount()).isZero();
        }

        /**
         * 明細が 1 件も無い注文を「全部取り消した」と言わないこと。
         * 空の注文（作りかけ）に対して、注文ごとのキャンセルが
         * 勝手に走ってしまうのを防ぎます。
         */
        @Test
        @DisplayName("明細が空の注文は「全部取り消した」ではない")
        void anEmptyOrderIsNotFullyCanceled() {
            Order empty = new Order(BUSINESS_DATE, 102, 10);
            assertThat(empty.isFullyCanceled()).isFalse();
        }
    }
}
