package jp.komeko.order.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仕入れの金額計算と、税率・控除率マスタの期間判定のテスト。
 *
 * <p><b>このテストが守っているもの</b>
 * <ul>
 *   <li><b>税込と税抜</b> … 原価率の分母と分子で税率が違うため、
 *       どちらで計算しているかを取り違えると数字が 0.5 ポイントずれます。
 *       小さく見えますが、原価率を 1 ポイント単位で見る店には無視できない差です。</li>
 *   <li><b>期間の境目</b> … 経過措置の控除率は 2026-09-30 と 10-01 で変わります。
 *       境目を 1 日間違えると、切り替わりの前後で控除額が狂います。
 *       日付の「以上・以下」は最も間違えやすい所なので、境界そのものを固定します。</li>
 * </ul>
 */
@DisplayName("仕入れの金額計算と期間の判定")
class PurchaseCalculationTest {

    private static final LocalDate ANY_DAY = LocalDate.of(2026, 8, 30);
    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 8, 30, 21, 0);

    private Purchase newPurchase(int totalAmount) {
        return new Purchase(ANY_DAY, ANY_DAY, "業務スーパー", totalAmount,
                PaymentMethod.CASH, STORED_AT);
    }

    @Nested
    @DisplayName("明細の税抜額")
    class NetAmount {

        @Test
        @DisplayName("税額の印字がなければ税率から逆算する")
        void derives_tax_when_not_printed() {
            // 適格簡易請求書は「税額」か「適用税率」のどちらか一方を書けばよいので、
            // 税額が印字されていないレシートは違法ではなく普通にある。
            PurchaseLine line = new PurchaseLine(1, "キャベツ", BigDecimal.ONE,
                    216, 8, null, PurchaseCategory.FOOD);

            // 216 × 8 ÷ 108 = 16 → 税抜 200
            assertThat(line.effectiveTaxAmount()).isEqualTo(16);
            assertThat(line.netAmount()).isEqualTo(200);
        }

        @Test
        @DisplayName("税額の印字があればそちらを優先する（レシートと 1 円も違わないため）")
        void prefers_printed_tax() {
            // 逆算すると 16 円になるところを、レシートには 15 円と印字されている場合。
            // 紙と画面が食い違うほうが実害が大きいので、印字を正とする。
            PurchaseLine line = new PurchaseLine(1, "キャベツ", BigDecimal.ONE,
                    216, 8, 15, PurchaseCategory.FOOD);

            assertThat(line.effectiveTaxAmount()).isEqualTo(15);
            assertThat(line.netAmount()).isEqualTo(201);
        }

        @Test
        @DisplayName("標準税率より低ければ軽減税率の印を付ける")
        void marks_reduced_rate() {
            PurchaseLine food = new PurchaseLine(1, "米粉", null, 1080, 8, null, PurchaseCategory.FOOD);
            PurchaseLine soap = new PurchaseLine(2, "洗剤", null, 550, 10, null, PurchaseCategory.SUPPLIES);

            assertThat(food.isReducedRate()).isTrue();
            assertThat(soap.isReducedRate()).isFalse();
        }
    }

    @Nested
    @DisplayName("1 枚のレシートの合計")
    class Totals {

        @Test
        @DisplayName("食材だけを分けて数える（原価率の分子は食材のみ）")
        void separates_food_cost() {
            // スーパーのレシートは 1 枚に食材と消耗品が混ざるのが普通。
            Purchase purchase = newPurchase(1630);
            purchase.addLine(new PurchaseLine(1, "キャベツ", null, 216, 8, null, PurchaseCategory.FOOD));
            purchase.addLine(new PurchaseLine(2, "米粉 1kg", null, 864, 8, null, PurchaseCategory.FOOD));
            purchase.addLine(new PurchaseLine(3, "洗剤", null, 550, 10, null, PurchaseCategory.SUPPLIES));

            assertThat(purchase.lineTotal()).isEqualTo(1630);
            // 食材だけ: 216 + 864 = 1080（洗剤は原価ではない）
            assertThat(purchase.foodCostTotalIncludingTax()).isEqualTo(1080);
            // 税抜: 200 + 800 = 1000
            assertThat(purchase.foodCostTotalNet()).isEqualTo(1000);
        }

        @Test
        @DisplayName("レシートの合計と明細の合算がずれたら、そのずれを出す")
        void reports_mismatch() {
            // 値引き行や読み取り漏れがあると合わない。黙って合わせず、見えるようにする。
            Purchase purchase = newPurchase(1000);
            purchase.addLine(new PurchaseLine(1, "キャベツ", null, 216, 8, null, PurchaseCategory.FOOD));

            assertThat(purchase.lineTotalMismatch()).isEqualTo(784);
        }
    }

    @Nested
    @DisplayName("紙の原本を捨ててよいか")
    class PaperRetention {

        @Test
        @DisplayName("同等確認をしていなければ捨ててはいけない")
        void requires_equivalence_check() {
            Purchase purchase = newPurchase(1000);

            assertThat(purchase.canDiscardPaper()).isFalse();

            purchase.markEquivalenceChecked(STORED_AT);
            assertThat(purchase.canDiscardPaper()).isTrue();
        }

        @Test
        @DisplayName("入力期限を過ぎた分は、確認しても紙を保管する")
        void keeps_paper_when_late() {
            // 期限を過ぎた書類はスキャナ保存に代えられない（紙のまま保存が要る）。
            Purchase purchase = newPurchase(1000);
            purchase.markEquivalenceChecked(STORED_AT);
            purchase.setPaperRetentionRequired(true);

            assertThat(purchase.canDiscardPaper()).isFalse();
        }
    }

    @Nested
    @DisplayName("控除率マスタの期間の境目")
    class DeductionPeriodBoundary {

        /** 経過措置 80%: 2023-10-01 〜 2026-09-30。 */
        private final DeductionRatePeriod eighty = new DeductionRatePeriod(
                80, LocalDate.of(2023, 10, 1), LocalDate.of(2026, 9, 30), null);

        /** 経過措置 70%: 2026-10-01 〜 2028-09-30（令和8年度改正で 50% から緩和）。 */
        private final DeductionRatePeriod seventy = new DeductionRatePeriod(
                70, LocalDate.of(2026, 10, 1), LocalDate.of(2028, 9, 30), null);

        @Test
        @DisplayName("最終日は含む（2026-09-30 はまだ 80%）")
        void includes_last_day() {
            assertThat(eighty.coversOn(LocalDate.of(2026, 9, 30))).isTrue();
            assertThat(seventy.coversOn(LocalDate.of(2026, 9, 30))).isFalse();
        }

        @Test
        @DisplayName("翌日から切り替わる（2026-10-01 は 70%）")
        void switches_next_day() {
            assertThat(eighty.coversOn(LocalDate.of(2026, 10, 1))).isFalse();
            assertThat(seventy.coversOn(LocalDate.of(2026, 10, 1))).isTrue();
        }

        @Test
        @DisplayName("開始日より前は対象外")
        void excludes_before_start() {
            assertThat(eighty.coversOn(LocalDate.of(2023, 9, 30))).isFalse();
        }

        @Test
        @DisplayName("終了日が null なら、その日以降ずっと有効")
        void open_ended_period() {
            DeductionRatePeriod ended = new DeductionRatePeriod(
                    0, LocalDate.of(2031, 10, 1), null, null);

            assertThat(ended.coversOn(LocalDate.of(2031, 10, 1))).isTrue();
            assertThat(ended.coversOn(LocalDate.of(2099, 1, 1))).isTrue();
            assertThat(ended.coversOn(LocalDate.of(2031, 9, 30))).isFalse();
        }

        @Test
        @DisplayName("帳簿の摘要に付ける文言（記載は義務）")
        void bookkeeping_note() {
            assertThat(eighty.bookkeepingNote()).isEqualTo("80%控除対象");
            assertThat(new DeductionRatePeriod(0, LocalDate.of(2031, 10, 1), null, null)
                    .bookkeepingNote()).isEqualTo("控除対象外");
        }
    }

    @Nested
    @DisplayName("税率マスタの期間の境目")
    class TaxPeriodBoundary {

        @Test
        @DisplayName("軽減税率 8% の期間を区切ると、翌日から次の率に渡せる")
        void can_be_split_for_future_change() {
            // 飲食料品 1%（2027-04-01〜）が成立したときは、
            // 既存行の終了日を入れて、次の行を足すだけで済む——という前提の確認。
            TaxRatePeriod current = new TaxRatePeriod(
                    TaxRatePeriod.CLASS_REDUCED_FOOD, 8,
                    LocalDate.of(2019, 10, 1), LocalDate.of(2027, 3, 31), null);
            TaxRatePeriod next = new TaxRatePeriod(
                    TaxRatePeriod.CLASS_REDUCED_FOOD, 1,
                    LocalDate.of(2027, 4, 1), LocalDate.of(2029, 3, 31), null);

            assertThat(current.coversOn(LocalDate.of(2027, 3, 31))).isTrue();
            assertThat(next.coversOn(LocalDate.of(2027, 3, 31))).isFalse();
            assertThat(current.coversOn(LocalDate.of(2027, 4, 1))).isFalse();
            assertThat(next.coversOn(LocalDate.of(2027, 4, 1))).isTrue();
        }
    }
}
