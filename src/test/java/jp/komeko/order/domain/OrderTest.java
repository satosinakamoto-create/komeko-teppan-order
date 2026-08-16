package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Order}（伝票 1 枚）のテスト。
 *
 * <p><b>このテストが守っているもの＝「伝票そのものの正しさ」</b><br>
 * Order は金額・状態・時刻をすべて抱えた、このアプリで一番重要なクラスです。
 * DB や Web を通さなくても、Java のオブジェクト単体でルールが守られていることを
 * ここで確かめておきます。
 *
 * <p><b>ドメインモデルを直接テストできることの価値</b><br>
 * 業務ルール（合計金額の出し方・状態遷移）を Order クラス自身に持たせているので、
 * Spring も DB も起動せずにテストできます。
 * もしこれらのルールをコントローラに書いていたら、
 * HTTP リクエストを組み立てないと 1 行も検証できませんでした。
 * 「ルールはドメイン（entity）に寄せる」ことの実利がここに出ます。
 */
@DisplayName("注文（伝票）のふるまい")
class OrderTest {

    /** テストで使う営業日。今日の日付に依存させないよう固定値にする。 */
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 16);

    /** 税率 8%（テイクアウトの軽減税率）・注文番号 101 の空の伝票を作る。 */
    private Order newOrder() {
        return new Order(BUSINESS_DATE, 101, 8);
    }

    @Nested
    @DisplayName("金額と調理見込みの再計算")
    class Recalculate {

        @Test
        @DisplayName("明細を足して recalculate() すると 合計・内税・調理見込み が求まる")
        void sumsUpLines() {
            Order order = newOrder();

            // コンプレット 880円 × 2個（1個あたり8分）
            order.addLine(new OrderLine(1L, "コンプレット", 880, 2, 8));
            // ドリップコーヒー 400円 × 1個（1個あたり2分）
            order.addLine(new OrderLine(2L, "ドリップコーヒー", 400, 1, 2));

            order.recalculate();

            assertThat(order.getLines()).hasSize(2);
            assertThat(order.getTotalQuantity()).isEqualTo(3);
            assertThat(order.getTotalAmount()).isEqualTo(2160);        // 880×2 + 400
            assertThat(order.getTaxAmount()).isEqualTo(160);           // 2160 × 8 ÷ 108
            assertThat(order.getNetAmount()).isEqualTo(2000);          // 2160 − 160
            assertThat(order.getEstimatedCookMinutes()).isEqualTo(18); // 8×2 + 2×1
        }

        @Test
        @DisplayName("recalculate() を呼ぶまで合計は 0 のまま（呼び忘れをテストで可視化する）")
        void doesNothingUntilCalled() {
            Order order = newOrder();
            order.addLine(new OrderLine(1L, "コンプレット", 880, 2, 8));

            // 明細を足しただけでは合計は更新されない、という現在の設計を明文化しておく。
            // 「なぜか金額が0円の伝票ができる」という不具合の原因はほぼこれ。
            assertThat(order.getTotalAmount()).isZero();

            order.recalculate();
            assertThat(order.getTotalAmount()).isEqualTo(1760);
        }

        @Test
        @DisplayName("オプション代（トッピング）が単価に乗って合計に反映される")
        void includesOptionPrices() {
            Order order = newOrder();

            OrderLine galette = new OrderLine(1L, "コンプレット", 880, 2, 8);
            // addOption を呼ぶと OrderLine 側で単価と小計が計算し直される
            galette.addOption(new OrderLineOption(10L, "トッピング", "チーズ増量", 150));
            order.addLine(galette);
            order.addLine(new OrderLine(2L, "ドリップコーヒー", 400, 1, 2));

            order.recalculate();

            assertThat(galette.getUnitPrice()).isEqualTo(1030);   // 880 + 150
            assertThat(galette.getLineTotal()).isEqualTo(2060);   // 1030 × 2
            assertThat(galette.getOptionSummary()).isEqualTo("チーズ増量");

            assertThat(order.getTotalAmount()).isEqualTo(2460);   // 2060 + 400
            assertThat(order.getTaxAmount()).isEqualTo(182);      // 2460 × 8 ÷ 108 = 182.2…
            assertThat(order.getNetAmount()).isEqualTo(2278);
        }

        @Test
        @DisplayName("addLine すると明細から伝票を辿れる（双方向関連が張られる）")
        void linksBothSides() {
            // 片側だけしかセットしないと、保存時に order_id が NULL になって落ちる。
            // JPA を使うときの定番の落とし穴なので、ここで固定しておく。
            Order order = newOrder();
            OrderLine line = new OrderLine(1L, "コンプレット", 880, 1, 8);

            order.addLine(line);

            assertThat(line.getOrder()).isSameAs(order);
        }
    }

    @Nested
    @DisplayName("状態の変更")
    class ChangeStatus {

        @Test
        @DisplayName("受付 → 調理中 → お渡し可 → 受渡済 と進められ、各時刻が記録される")
        void happyPath() {
            Order order = newOrder();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.RECEIVED);

            order.changeStatus(OrderStatus.COOKING, "店長");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COOKING);
            // 「いつ焼き始めたか」が残っていないと、あとで待ち時間の検証ができない
            assertThat(order.getCookingStartedAt()).isNotNull();
            assertThat(order.getLastHandledBy()).isEqualTo("店長");

            order.changeStatus(OrderStatus.READY, "厨房スタッフ");
            assertThat(order.getReadyAt()).isNotNull();
            assertThat(order.getLastHandledBy()).isEqualTo("厨房スタッフ");

            order.changeStatus(OrderStatus.COMPLETED, "店長");
            assertThat(order.getCompletedAt()).isNotNull();
            assertThat(order.getStatus().isClosed()).isTrue();
        }

        @Test
        @DisplayName("許可されていない遷移は IllegalStateException になる")
        void rejectsForbiddenTransition() {
            Order order = newOrder();

            // 受付からいきなり受渡済（会計まで完了）にはできない。
            //
            // assertThatThrownBy は「この処理を実行したら例外が出るはず」を書くための書き方。
            // try/catch + fail() より読みやすく、
            // 「例外が出なかった」ケースもきちんと失敗として検出してくれる。
            assertThatThrownBy(() -> order.changeStatus(OrderStatus.COMPLETED, "店長"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("受付")
                    .hasMessageContaining("受渡済")
                    .hasMessageContaining("変更できません");

            // 例外が出たあとも状態が変わっていないこと（中途半端に壊れない）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.RECEIVED);
            assertThat(order.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("受渡済まで進んだ伝票はそれ以上動かせない")
        void completedIsFrozen() {
            Order order = newOrder();
            order.changeStatus(OrderStatus.READY, "店長");
            order.changeStatus(OrderStatus.COMPLETED, "店長");

            assertThatThrownBy(() -> order.changeStatus(OrderStatus.COOKING, "店長"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> order.changeStatus(OrderStatus.CANCELED, "店長"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("同じ状態への変更は例外にならない（ボタンの二度押し対策）")
        void sameStatusIsIgnored() {
            // タブレットは反応が鈍いと二度押ししがち。
            // ここで例外にすると、現場では「エラー画面が出た！」という事故になる。
            // 何も起きずに黙って無視するのが正しいふるまい。
            Order order = newOrder();
            order.changeStatus(OrderStatus.COOKING, "店長");

            assertThatCode(() -> order.changeStatus(OrderStatus.COOKING, "厨房スタッフ"))
                    .doesNotThrowAnyException();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.COOKING);
            // 二度押しでは何も更新しないので、担当者も上書きされない
            assertThat(order.getLastHandledBy()).isEqualTo("店長");
        }

        @Test
        @DisplayName("受付直後はお客さん自身でキャンセルできるが、焼き始めたらできない")
        void customerCancelableOnlyBeforeCooking() {
            Order order = newOrder();
            assertThat(order.isCustomerCancelable()).isTrue();

            order.changeStatus(OrderStatus.COOKING, "店長");
            // すでに材料を使ってしまっているので、お客さん都合の取り消しは受けない
            assertThat(order.isCustomerCancelable()).isFalse();
        }
    }

    @Nested
    @DisplayName("キャンセル")
    class Cancel {

        @Test
        @DisplayName("cancel() で 理由・時刻・担当者 が記録される")
        void recordsReasonAndTime() {
            // 「なぜキャンセルになったか」が残っていないと、
            // 翌日に売上を見返したときに原因を追えない。
            Order order = newOrder();

            order.cancel("材料切れ", "店長");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(order.getCanceledReason()).isEqualTo("材料切れ");
            assertThat(order.getCanceledAt()).isNotNull();
            assertThat(order.getLastHandledBy()).isEqualTo("店長");
        }

        @Test
        @DisplayName("調理中からでもキャンセルできる")
        void canCancelWhileCooking() {
            Order order = newOrder();
            order.changeStatus(OrderStatus.COOKING, "店長");

            order.cancel("お客様が来店されなかったため", "店長");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        }

        @Test
        @DisplayName("受渡済の伝票をキャンセルしようとすると例外になり、理由も書き込まれない")
        void cannotCancelCompleted() {
            Order order = newOrder();
            order.changeStatus(OrderStatus.READY, "店長");
            order.changeStatus(OrderStatus.COMPLETED, "店長");

            assertThatThrownBy(() -> order.cancel("間違えた", "店長"))
                    .isInstanceOf(IllegalStateException.class);

            // cancel() は先に状態遷移を行うので、例外で止まれば理由は書かれない。
            // 「例外は出たけど一部だけ更新されていた」が一番たちの悪いバグなので確認する。
            assertThat(order.getCanceledReason()).isNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("表示・記録まわり")
    class Presentation {

        @Test
        @DisplayName("受付直後の経過時間は 0 分から始まる")
        void elapsedMinutesStartsAtZero() {
            // 厨房ボードの「◯分経過」表示のもと。負の値になると現場が混乱する。
            Order order = newOrder();

            assertThat(order.getElapsedMinutes()).isZero();
        }

        @Test
        @DisplayName("注文ごとに推測できない公開トークンが振られる")
        void publicTokenIsUniqueAndLong() {
            // /o/{token} はログイン無しで見られる URL なので、
            // 連番など推測できる値だと他人の注文が覗けてしまう。
            Order a = newOrder();
            Order b = newOrder();

            assertThat(a.getPublicToken()).isNotBlank();
            assertThat(a.getPublicToken()).isNotEqualTo(b.getPublicToken());
            assertThat(a.getPublicToken().length()).isGreaterThanOrEqualTo(32);
        }

        @Test
        @DisplayName("注文時点の税率・営業日・番号が伝票に保存される（あとで設定を変えても影響しない）")
        void snapshotsTaxRate() {
            Order order = newOrder();

            assertThat(order.getTaxRatePercent()).isEqualTo(8);
            assertThat(order.getBusinessDate()).isEqualTo(BUSINESS_DATE);
            assertThat(order.getOrderNumber()).isEqualTo(101);
        }
    }
}
