package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.config.InventoryProperties;
import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.repository.PurchaseRepository;
import jp.komeko.order.inventory.repository.SalesLookupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link PurchaseService} の判断ルールのテスト。
 *
 * <p>DB を触らない判断（入力期限・証憑区分のたたき台）だけをここで固定します。
 * どちらも<b>間違えると法律上の扱いが変わる</b>ところなので、
 * 境目の日付と金額を数字で残しておきます。
 */
@DisplayName("仕入れの判断ルール（入力期限・証憑区分）")
class PurchaseServiceRulesTest {

    /** テスト中の「今日」。時計を固定しないと、日付の判定はテストできない。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    private PurchaseService service;

    @BeforeEach
    void setUp() {
        InventoryProperties properties = new InventoryProperties(
                true, 7, 90, 1_000_000L, new InventoryProperties.Ocr(null, null, 0));
        Clock fixed = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        service = new PurchaseService(
                mock(PurchaseRepository.class),
                mock(SalesLookupRepository.class),
                mock(TaxRuleService.class),
                mock(jp.komeko.order.inventory.repository.IngredientRepository.class),
                mock(IngredientService.class),
                properties,
                fixed);
    }

    @Nested
    @DisplayName("入力期限（電子帳簿保存法）")
    class InputDeadline {

        @Test
        @DisplayName("受領当日に登録すれば当然セーフ")
        void same_day_is_fine() {
            assertThat(service.isPastInputDeadline(TODAY, TODAY)).isFalse();
        }

        @Test
        @DisplayName("7日後まではセーフ、8日後からアウト（境目を固定する）")
        void boundary_is_seven_days() {
            LocalDate received = LocalDate.of(2026, 8, 1);

            assertThat(service.isPastInputDeadline(received, LocalDate.of(2026, 8, 8))).isFalse();
            assertThat(service.isPastInputDeadline(received, LocalDate.of(2026, 8, 9))).isTrue();
        }

        @Test
        @DisplayName("日付が分からないときは期限切れにしない（記録を残せなくなるより良い）")
        void null_is_not_late() {
            assertThat(service.isPastInputDeadline(null, TODAY)).isFalse();
            assertThat(service.isPastInputDeadline(TODAY, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("証憑区分のたたき台（最終判断は人）")
    class EvidenceSuggestion {

        @Test
        @DisplayName("登録番号があれば適格簡易請求書")
        void with_registration_number() {
            assertThat(service.suggestEvidenceType("T7000012050002", 30_000, TODAY))
                    .isEqualTo(EvidenceType.SIMPLIFIED_INVOICE);
        }

        @Test
        @DisplayName("登録番号が無くても1万円未満なら少額特例の候補")
        void small_amount_without_number() {
            // 1万円未満は帳簿だけで控除できる（2029-09-30 まで）。
            // ただし使えるのは基準期間の課税売上高が1億円以下などの事業者に限られるので、
            // あくまで「候補」として出すだけ。
            assertThat(service.suggestEvidenceType(null, 9_999, TODAY))
                    .isEqualTo(EvidenceType.BOOK_ONLY_SPECIAL);
        }

        @Test
        @DisplayName("1万円ちょうどは少額特例の対象外（「未満」なので）")
        void exactly_ten_thousand_is_out() {
            assertThat(service.suggestEvidenceType(null, 10_000, TODAY))
                    .isEqualTo(EvidenceType.NOT_QUALIFIED);
        }

        @Test
        @DisplayName("少額特例が終わる2029-09-30を過ぎたら候補にしない")
        void small_amount_special_expires() {
            assertThat(service.suggestEvidenceType(null, 5_000, LocalDate.of(2029, 9, 30)))
                    .isEqualTo(EvidenceType.BOOK_ONLY_SPECIAL);
            assertThat(service.suggestEvidenceType(null, 5_000, LocalDate.of(2029, 10, 1)))
                    .isEqualTo(EvidenceType.NOT_QUALIFIED);
        }

        /**
         * OCR が合計を読めなかったレシート（かすれ・読取失敗）で頻発する経路。
         * 以前は null を 0 円として扱い、全部「1 万円未満 → 帳簿のみ特例（全額控除）」の
         * 候補になっていた。分からないときは控除の少ない側（経過措置）に倒す。
         */
        @Test
        @DisplayName("合計が読めていない（null）ときは、少額特例を候補にしない")
        void unknown_total_is_not_small_amount() {
            assertThat(service.suggestEvidenceType(null, null, TODAY))
                    .isEqualTo(EvidenceType.NOT_QUALIFIED);
        }
    }
}
