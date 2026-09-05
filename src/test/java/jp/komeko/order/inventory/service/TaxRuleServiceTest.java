package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.config.InventoryProperties;
import jp.komeko.order.inventory.domain.DeductionRatePeriod;
import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.domain.TaxRatePeriod;
import jp.komeko.order.inventory.repository.DeductionRatePeriodRepository;
import jp.komeko.order.inventory.repository.TaxRatePeriodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TaxRuleService} のテスト。
 *
 * <p><b>このテストが守っているもの＝「制度が変わっても壊れない」という約束</b><br>
 * 税率も控除率もコードに書かず、日付でマスタを引く設計にしています。
 * その約束が本当に守られているか（率がハードコードされていないか、
 * 行が無いときに黙って間違えないか）をここで固定します。
 *
 * <p><b>なぜ mock を使うのか</b><br>
 * 確かめたいのは「DB から取れた行のうち、どれを選ぶか」という判断だけで、
 * DB そのものではありません。Spring も DB も起動せず、
 * リポジトリの戻り値だけを差し替えれば、ミリ秒で何度でも回せます。
 */
@DisplayName("税率・控除率の解決（日付で引く）")
class TaxRuleServiceTest {

    private TaxRatePeriodRepository taxRates;
    private DeductionRatePeriodRepository deductionRates;
    private TaxRuleService service;

    /** 経過措置 80%: 〜2026-09-30。 */
    private final DeductionRatePeriod eighty = new DeductionRatePeriod(
            80, LocalDate.of(2023, 10, 1), LocalDate.of(2026, 9, 30), null);

    /** 経過措置 70%: 2026-10-01〜。令和8年度改正で 50% から緩和された。 */
    private final DeductionRatePeriod seventy = new DeductionRatePeriod(
            70, LocalDate.of(2026, 10, 1), LocalDate.of(2028, 9, 30), null);

    @BeforeEach
    void setUp() {
        taxRates = mock(TaxRatePeriodRepository.class);
        deductionRates = mock(DeductionRatePeriodRepository.class);
        InventoryProperties properties = new InventoryProperties(
                true, 7, 90, 1_000_000L, new InventoryProperties.Ocr(null, null, 0));
        service = new TaxRuleService(taxRates, deductionRates, properties);
    }

    @Nested
    @DisplayName("税率を引く")
    class TaxRate {

        @Test
        @DisplayName("その日に有効な行の率を返す")
        void returns_active_rate() {
            LocalDate day = LocalDate.of(2026, 8, 30);
            when(taxRates.findActive(eq(TaxRatePeriod.CLASS_REDUCED_FOOD), eq(day)))
                    .thenReturn(List.of(new TaxRatePeriod(
                            TaxRatePeriod.CLASS_REDUCED_FOOD, 8, LocalDate.of(2019, 10, 1), null, null)));

            assertThat(service.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, day)).isEqualTo(8);
        }

        @Test
        @DisplayName("行が無ければ標準税率で代替する（止めない）")
        void falls_back_when_master_missing() {
            // 代替値を使うこと自体は事故だが、ここで例外にすると記録が一切残せなくなる。
            // 代わりに masterWarnings で人に知らせる、という役割分担にしている。
            when(taxRates.findActive(any(), any())).thenReturn(List.of());

            assertThat(service.taxRateOn(TaxRatePeriod.CLASS_STANDARD, LocalDate.of(2026, 8, 30)))
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("期間が重なっていたら、新しく始まったほうを採る")
        void prefers_newer_when_overlapping() {
            LocalDate day = LocalDate.of(2027, 5, 1);
            // findActive は valid_from の降順で返す約束（誤って重ねて登録した場合の保険）
            when(taxRates.findActive(eq(TaxRatePeriod.CLASS_REDUCED_FOOD), eq(day)))
                    .thenReturn(List.of(
                            new TaxRatePeriod(TaxRatePeriod.CLASS_REDUCED_FOOD, 1,
                                    LocalDate.of(2027, 4, 1), null, "新しい行"),
                            new TaxRatePeriod(TaxRatePeriod.CLASS_REDUCED_FOOD, 8,
                                    LocalDate.of(2019, 10, 1), null, "古い行")));

            assertThat(service.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, day)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("控除率を引く")
    class DeductionRate {

        @Test
        @DisplayName("登録番号のある証憑は全額（100%）引ける")
        void qualified_invoice_is_fully_deductible() {
            assertThat(service.deductionRateOn(EvidenceType.SIMPLIFIED_INVOICE, LocalDate.of(2026, 8, 30)))
                    .isEqualTo(100);
            assertThat(service.deductionRateOn(EvidenceType.QUALIFIED_INVOICE, LocalDate.of(2026, 8, 30)))
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("少額特例も全額引ける")
        void small_amount_special_is_fully_deductible() {
            assertThat(service.deductionRateOn(EvidenceType.BOOK_ONLY_SPECIAL, LocalDate.of(2026, 8, 30)))
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("2026-09-30 の仕入れは 80%、10-01 からは 70%（境目を間違えない）")
        void uses_transitional_rate_by_date() {
            LocalDate lastDayOfEighty = LocalDate.of(2026, 9, 30);
            LocalDate firstDayOfSeventy = LocalDate.of(2026, 10, 1);
            when(deductionRates.findActive(eq(lastDayOfEighty))).thenReturn(List.of(eighty));
            when(deductionRates.findActive(eq(firstDayOfSeventy))).thenReturn(List.of(seventy));

            assertThat(service.deductionRateOn(EvidenceType.NOT_QUALIFIED, lastDayOfEighty)).isEqualTo(80);
            assertThat(service.deductionRateOn(EvidenceType.NOT_QUALIFIED, firstDayOfSeventy)).isEqualTo(70);
        }

        @Test
        @DisplayName("行が無ければ安全側に倒して 0%（引けない扱い）")
        void falls_back_to_zero() {
            // 税率の代替は「標準税率」でよいが、控除率の代替は多いほうに倒すと
            // 引きすぎになる。少なく見積もるほうが安全。
            when(deductionRates.findActive(any())).thenReturn(List.of());

            assertThat(service.deductionRateOn(EvidenceType.NOT_QUALIFIED, LocalDate.of(2026, 8, 30)))
                    .isZero();
        }

        @Test
        @DisplayName("帳簿の摘要は、全額引けるときは付けない")
        void bookkeeping_note() {
            assertThat(service.bookkeepingNoteFor(100)).isNull();
            assertThat(service.bookkeepingNoteFor(70)).isEqualTo("70%控除対象");
            assertThat(service.bookkeepingNoteFor(0)).isEqualTo("控除対象外");
        }
    }

    @Nested
    @DisplayName("マスタ終端警告（制度変更の見落としを防ぐ保険）")
    class MasterWarnings {

        @Test
        @DisplayName("マスタが揃っていれば何も言わない")
        void silent_when_healthy() {
            LocalDate today = LocalDate.of(2026, 8, 30);
            when(taxRates.findActive(any(), any())).thenReturn(List.of(
                    new TaxRatePeriod(TaxRatePeriod.CLASS_STANDARD, 10, LocalDate.of(2019, 10, 1), null, null)));
            when(deductionRates.findActive(any())).thenReturn(List.of(eighty));
            when(taxRates.findExpiringFrom(any())).thenReturn(List.of());
            when(deductionRates.findExpiringFrom(any())).thenReturn(List.of());

            assertThat(service.masterWarnings(today)).isEmpty();
        }

        @Test
        @DisplayName("その日に有効な行が無ければ警告する")
        void warns_when_no_active_row() {
            LocalDate today = LocalDate.of(2026, 8, 30);
            when(taxRates.findActive(any(), any())).thenReturn(List.of());
            when(deductionRates.findActive(any())).thenReturn(List.of());
            when(taxRates.findExpiringFrom(any())).thenReturn(List.of());
            when(deductionRates.findExpiringFrom(any())).thenReturn(List.of());

            // 標準・軽減の 2 区分 + 控除率で 3 件
            assertThat(service.masterWarnings(today)).hasSize(3);
            assertThat(service.masterWarnings(today))
                    .anyMatch(w -> w.contains("代替"));
        }

        @Test
        @DisplayName("期限が近いのに次の行が無ければ警告する（改正の見落とし）")
        void warns_when_next_row_missing() {
            // 2026-08-30 の 90 日後は 2026-11-28。80% は 09-30 で終わるので範囲内。
            LocalDate today = LocalDate.of(2026, 8, 30);
            when(taxRates.findActive(any(), any())).thenReturn(List.of(
                    new TaxRatePeriod(TaxRatePeriod.CLASS_STANDARD, 10, LocalDate.of(2019, 10, 1), null, null)));
            when(taxRates.findExpiringFrom(any())).thenReturn(List.of());
            when(deductionRates.findActive(eq(today))).thenReturn(List.of(eighty));
            when(deductionRates.findExpiringFrom(eq(today))).thenReturn(List.of(eighty));
            // 翌日（2026-10-01）に有効な行が無い＝次が登録されていない
            when(deductionRates.findActive(eq(LocalDate.of(2026, 10, 1)))).thenReturn(List.of());

            assertThat(service.masterWarnings(today))
                    .anyMatch(w -> w.contains("その翌日から使う行がありません"));
        }

        @Test
        @DisplayName("次の行が登録されていれば警告しない")
        void silent_when_next_row_exists() {
            LocalDate today = LocalDate.of(2026, 8, 30);
            when(taxRates.findActive(any(), any())).thenReturn(List.of(
                    new TaxRatePeriod(TaxRatePeriod.CLASS_STANDARD, 10, LocalDate.of(2019, 10, 1), null, null)));
            when(taxRates.findExpiringFrom(any())).thenReturn(List.of());
            when(deductionRates.findActive(eq(today))).thenReturn(List.of(eighty));
            when(deductionRates.findExpiringFrom(eq(today))).thenReturn(List.of(eighty));
            when(deductionRates.findActive(eq(LocalDate.of(2026, 10, 1)))).thenReturn(List.of(seventy));

            assertThat(service.masterWarnings(today)).isEmpty();
        }
    }
}
