package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.repository.IngredientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在庫計算のテスト。
 *
 * <p><b>ここが間違うと、店の判断そのものが狂います。</b>
 * 「まだある」と表示されたから発注しなかった、という形で実害が出ます。
 * だから金額計算と同じ厚さでテストを書きます。
 *
 * <p>特に固定したいのは、設計文書で「間違えやすい」と名指しした 2 点です:
 * <ol>
 *   <li>「直近の棚卸し」は<b>対象時点以前の</b>直近であること</li>
 *   <li>棚卸しと同じ日の仕入れを二重に数えないこと</li>
 * </ol>
 *
 * <p>DB を使うのは、集計のクエリそのものも検証したいからです。
 * 計算だけをモックで確かめても、SQL の条件を書き間違えれば本番で壊れます。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("在庫の計算")
class StockServiceTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private IngredientService ingredientService;

    private Ingredient cabbage;

    @BeforeEach
    void setUp() {
        // テストごとに別の食材を作る。テスト間で在庫が混ざらないようにするため。
        String name = "テスト用キャベツ-" + System.nanoTime();
        cabbage = ingredients.save(new Ingredient(name, IngredientUnit.GRAM));
    }

    /** 指定日に仕入れを 1 本立てる（この食材に stockQty ぶん入庫する）。 */
    private void purchase(LocalDate on, int amount, BigDecimal stockQty) {
        PurchaseDraft draft = new PurchaseDraft(
                on, on, "テスト八百屋", amount, PaymentMethod.CASH,
                null, EvidenceType.NOT_QUALIFIED, null, null, null, false,
                List.of(new PurchaseDraft.LineDraft(
                        "テスト品-" + System.nanoTime(), BigDecimal.ONE, amount, 8, null,
                        PurchaseCategory.FOOD, cabbage.getId(), stockQty, false)));
        purchaseService.record(draft, null);
    }

    private BigDecimal quantityOn(LocalDate date) {
        for (StockLevel level : stockService.levelsAsOf(date)) {
            if (level.ingredient().getId().equals(cabbage.getId())) {
                return level.quantity();
            }
        }
        throw new AssertionError("食材が一覧に出てきませんでした");
    }

    @Test
    @DisplayName("棚卸しが一度も無ければ、0 を起点に仕入れだけを足す")
    void without_stocktake_starts_from_zero() {
        purchase(LocalDate.of(2026, 8, 1), 300, new BigDecimal("1000"));
        purchase(LocalDate.of(2026, 8, 5), 300, new BigDecimal("500"));

        assertThat(quantityOn(LocalDate.of(2026, 8, 10)))
                .isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("棚卸しより前の仕入れは忘れる（二重に数えない）")
    void stocktake_absorbs_everything_before_it() {
        purchase(LocalDate.of(2026, 8, 1), 300, new BigDecimal("1000"));

        // 8/10 に数えたら 800g しか無かった（打ち粉やまかないで減っていた）
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("800"), null, null);

        // 1000 + 800 = 1800 にはならない。実測がすべてを吸収する。
        assertThat(quantityOn(LocalDate.of(2026, 8, 10)))
                .isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("棚卸しと同じ日の仕入れは数えない（実測に含まれているとみなす）")
    void purchase_on_the_same_day_is_not_counted_twice() {
        // 朝に仕入れ、閉店後に数えた、という 1 日の流れ
        purchase(LocalDate.of(2026, 8, 10), 300, new BigDecimal("500"));
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("800"), null, null);

        // 800 + 500 = 1300 にしてしまうと、棚に無い 500g を「ある」と言うことになる
        assertThat(quantityOn(LocalDate.of(2026, 8, 10)))
                .isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("棚卸しの翌日以降の仕入れは足す")
    void purchase_after_stocktake_is_added() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("800"), null, null);
        purchase(LocalDate.of(2026, 8, 11), 300, new BigDecimal("500"));

        assertThat(quantityOn(LocalDate.of(2026, 8, 15)))
                .isEqualByComparingTo("1300");
    }

    @Test
    @DisplayName("★ 過去の在庫を聞かれたら、その時点以前の棚卸しを起点にする")
    void past_stock_uses_the_baseline_that_was_current_then() {
        // 8/1 に 1000g と数えた
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 1),
                new BigDecimal("1000"), null, null);
        // 8/3 に 200g 仕入れた
        purchase(LocalDate.of(2026, 8, 3), 100, new BigDecimal("200"));
        // 8/20 に数え直したら 400g だった
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 20),
                new BigDecimal("400"), null, null);

        // 8/5 時点を聞かれたら、使うのは 8/1 の棚卸し。8/20 の実測ではない。
        // ここを間違えると、過去に向かって計算することになり答えが逆に動く。
        assertThat(quantityOn(LocalDate.of(2026, 8, 5)))
                .isEqualByComparingTo("1200");

        // いまを聞かれたら 8/20 の実測が起点
        assertThat(quantityOn(LocalDate.of(2026, 8, 25)))
                .isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("廃棄は在庫を減らす")
    void waste_reduces_stock() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("1000"), null, null);
        stockService.recordAdjustment(cabbage.getId(), LocalDate.of(2026, 8, 12),
                new BigDecimal("-300"), StocktakeReason.WASTE, "傷んでいた", null);

        assertThat(quantityOn(LocalDate.of(2026, 8, 15)))
                .isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("棚卸しと同じ日でも、数えたあとに記録した廃棄は数える")
    void adjustment_recorded_after_the_count_still_counts() {
        // 「数えたあとで 3 パック捨てた」を落とさない。
        // 日付だけで切ると、この廃棄が消えて在庫が多めに出る。
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("1000"), null, null);
        stockService.recordAdjustment(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("-300"), StocktakeReason.WASTE, null, null);

        assertThat(quantityOn(LocalDate.of(2026, 8, 10)))
                .isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("マイナスをそのまま出す（0 で止めない）")
    void negative_stock_is_shown_as_is() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.of(2026, 8, 10),
                new BigDecimal("100"), null, null);
        stockService.recordAdjustment(cabbage.getId(), LocalDate.of(2026, 8, 11),
                new BigDecimal("-500"), StocktakeReason.WASTE, null, null);

        BigDecimal quantity = quantityOn(LocalDate.of(2026, 8, 12));
        assertThat(quantity).isEqualByComparingTo("-400");

        // 0 で止めて表示すると「正しく動いている」ように見えてしまい、
        // 棚卸しが必要なことに人が気づけなくなる。
        StockLevel level = stockService.levelOf(cabbage.getId());
        assertThat(level.isNegative()).isTrue();
    }

    @Test
    @DisplayName("警告残量を下回ったら注意が要る状態になる")
    void below_threshold_needs_attention() {
        cabbage.setLowThresholdQty(new BigDecimal("500"));
        ingredients.save(cabbage);

        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(1),
                new BigDecimal("300"), null, null);

        StockLevel level = stockService.levelOf(cabbage.getId());
        assertThat(level.isBelowThreshold()).isTrue();
        assertThat(level.needsAttention()).isTrue();
    }

    @Test
    @DisplayName("単価は最新の仕入れから出す（値上がりが翌日に効く）")
    void unit_cost_follows_the_latest_purchase() {
        purchase(LocalDate.of(2026, 8, 1), 100, new BigDecimal("1000"));   // 0.1 円/g
        purchase(LocalDate.of(2026, 8, 5), 300, new BigDecimal("1000"));   // 0.3 円/g

        StockLevel level = stockService.levelOf(cabbage.getId());
        assertThat(level.unitCostIncludingTax()).isEqualByComparingTo("0.3");
        assertThat(level.costIsOverridden()).isFalse();
    }

    @Test
    @DisplayName("単価を手で固定したら、仕入れ実績より優先する")
    void cost_override_wins() {
        purchase(LocalDate.of(2026, 8, 1), 100, new BigDecimal("1000"));
        cabbage.setCostOverride(new BigDecimal("0.5"));
        ingredients.save(cabbage);

        StockLevel level = stockService.levelOf(cabbage.getId());
        assertThat(level.unitCostIncludingTax()).isEqualByComparingTo("0.5");
        assertThat(level.costIsOverridden()).isTrue();
    }

    @Test
    @DisplayName("量を教わっていない仕入れは在庫に積まれない（金額の記録は残る）")
    void line_without_quantity_does_not_feed_stock() {
        purchase(LocalDate.of(2026, 8, 1), 120, null);

        assertThat(quantityOn(LocalDate.of(2026, 8, 10)))
                .isEqualByComparingTo("0");
        // ただし「宿題」としては見えている必要がある
        assertThat(ingredientService.linesNeedingQuantity()).isNotEmpty();
    }
}
