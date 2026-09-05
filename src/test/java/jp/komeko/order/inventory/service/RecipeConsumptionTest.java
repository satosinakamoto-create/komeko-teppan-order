package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.repository.RecipeLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レシピによる消費計算と、欠品予測のテスト。
 *
 * <p><b>ここが間違うと、発注の判断そのものが狂います。</b>
 * 「あと 5 営業日ある」と言われて発注しなかったのに翌日切れた、という形で
 * 実害が出ます。金額計算と同じ厚さで書きます。
 *
 * <p>とくに固定したいのは:
 * <ol>
 *   <li>キャンセルした注文の材料は減らないこと（作っていないので）</li>
 *   <li>営業日は「注文があった日」で数えること（定休日を勝手に除く）</li>
 *   <li>予測日数は切り捨てること（足りない側に丸める）</li>
 *   <li>消費の実績がないとき、日数を出さないこと（嘘をつかない）</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("レシピによる消費と欠品予測")
class RecipeConsumptionTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private ConsumptionService consumptionService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private RecipeLineRepository recipes;

    @Autowired
    private InventoryTestFixture fixture;

    private Ingredient cabbage;
    private MenuItem okonomiyaki;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        cabbage = ingredients.save(new Ingredient("消費テスト用キャベツ-" + suffix, IngredientUnit.GRAM));
        okonomiyaki = fixture.createMenuItem("消費テスト用お好み焼き-" + suffix, 1000);
    }

    /** 1 品につきキャベツ 100g を使うレシピにする。 */
    private void registerRecipe(BigDecimal qtyPerItem) {
        recipeService.addLine(okonomiyaki.getId(), cabbage.getId(), qtyPerItem, null);
    }

    /** 指定営業日に、その商品を quantity 個売る。 */
    private void sell(LocalDate businessDate, int quantity, OrderStatus status) {
        fixture.placeOrder(okonomiyaki, businessDate, quantity, status);
    }

    private BigDecimal stockOn(LocalDate date) {
        for (StockLevel level : stockService.levelsAsOf(date)) {
            if (level.ingredient().getId().equals(cabbage.getId())) {
                return level.quantity();
            }
        }
        throw new AssertionError("食材が一覧に出てきませんでした");
    }

    @Test
    @DisplayName("売れた分だけ食材が減る")
    void sold_items_consume_ingredients() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("2000"), null, null);
        registerRecipe(new BigDecimal("100"));
        sell(LocalDate.now().minusDays(5), 6, OrderStatus.COMPLETED);

        // 2000 − (6 × 100) = 1400
        assertThat(stockOn(LocalDate.now())).isEqualByComparingTo("1400");
    }

    @Test
    @DisplayName("キャンセルした注文は材料を減らさない")
    void canceled_orders_do_not_consume() {
        // 作らなかった料理の材料は減らない。会計から外す判定と同じものを使う。
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("2000"), null, null);
        registerRecipe(new BigDecimal("100"));
        sell(LocalDate.now().minusDays(5), 6, OrderStatus.CANCELED);

        assertThat(stockOn(LocalDate.now())).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("受渡前（調理中）でも材料は減っている")
    void cooking_orders_already_consume() {
        // 売上は受渡済みだけ数えるが、材料は火を入れた時点でもう減っている。
        // お金と材料は減るタイミングが違う。
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("2000"), null, null);
        registerRecipe(new BigDecimal("100"));
        sell(LocalDate.now().minusDays(1), 3, OrderStatus.COOKING);

        assertThat(stockOn(LocalDate.now())).isEqualByComparingTo("1700");
    }

    @Test
    @DisplayName("棚卸しより前に売れた分は数えない（実測が吸収している）")
    void sales_before_the_stocktake_are_forgotten() {
        registerRecipe(new BigDecimal("100"));
        sell(LocalDate.now().minusDays(20), 10, OrderStatus.COMPLETED);
        // そのあと数え直したら 500g だった
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("500"), null, null);
        sell(LocalDate.now().minusDays(5), 2, OrderStatus.COMPLETED);

        // 500 − (2 × 100) = 300。20日前の10個ぶんは引かない。
        assertThat(stockOn(LocalDate.now())).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("レシピ未登録なら消費されない（そして未登録として警告に出る）")
    void without_recipe_nothing_is_consumed() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("2000"), null, null);
        sell(LocalDate.now().minusDays(5), 6, OrderStatus.COMPLETED);

        assertThat(stockOn(LocalDate.now())).isEqualByComparingTo("2000");

        // 黙って漏れるのがいちばん危ないので、必ず一覧に出ていること
        assertThat(recipeService.menuItemsWithoutRecipe())
                .extracting(MenuItem::getId)
                .contains(okonomiyaki.getId());
    }

    @Test
    @DisplayName("★ あと何営業日もつかを出す（切り捨て）")
    void forecasts_days_left() {
        // ここは「その日の店に注文があったか」を全店ぶんで数えるので、
        // ほかのテストが作った注文の影響を受けます。
        // 誰も触らない過去の日付を基準にして、窓をまるごと分離します。
        LocalDate base = LocalDate.now().minusDays(200);

        // 4 週間の窓のうち 2 日だけ営業し、そこで 10 個ずつ売った。
        // 1 営業日あたり 10 個 × 100g = 1000g 使う。
        registerRecipe(new BigDecimal("100"));
        sell(base.minusDays(3), 10, OrderStatus.COMPLETED);
        sell(base.minusDays(2), 10, OrderStatus.COMPLETED);

        // その時点で 2500g ある状態にする
        stockService.recordStocktake(cabbage.getId(), base, new BigDecimal("2500"), null, null);

        StockLevel level = levelAsOf(base);
        assertThat(level.dailyConsumption()).isEqualByComparingTo("1000");

        // 2500 ÷ 1000 = 2.5 → 「あと 2 営業日」。
        // 切り上げて 3 と言うと、3 日目に切らして仕込みが止まる。
        assertThat(level.daysLeft()).isEqualTo(2);
    }

    private StockLevel levelAsOf(LocalDate date) {
        for (StockLevel level : stockService.levelsAsOf(date)) {
            if (level.ingredient().getId().equals(cabbage.getId())) {
                return level;
            }
        }
        throw new AssertionError("食材が一覧に出てきませんでした");
    }

    @Test
    @DisplayName("消費の実績がなければ日数を出さない（嘘をつかない）")
    void no_forecast_without_evidence() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(1),
                new BigDecimal("2000"), null, null);

        // レシピも売上も無い。「あと 999 日」のような数字を出すくらいなら黙る。
        StockLevel level = stockService.levelOf(cabbage.getId());
        assertThat(level.daysLeft()).isNull();
        assertThat(level.dailyConsumption()).isNull();
    }

    @Test
    @DisplayName("在庫が尽きていれば「あと0営業日」")
    void zero_days_when_empty() {
        LocalDate base = LocalDate.now().minusDays(300);
        registerRecipe(new BigDecimal("100"));
        sell(base.minusDays(2), 10, OrderStatus.COMPLETED);
        stockService.recordStocktake(cabbage.getId(), base, BigDecimal.ZERO, null, null);

        assertThat(levelAsOf(base).daysLeft()).isZero();
    }

    @Test
    @DisplayName("営業日は「注文があった日」で数える（定休日は自動で除かれる）")
    void business_days_come_from_actual_orders() {
        LocalDate base = LocalDate.now().minusDays(400);
        registerRecipe(new BigDecimal("100"));
        // 4 週間のうち、注文があったのは 2 日だけ
        sell(base.minusDays(10), 5, OrderStatus.COMPLETED);
        sell(base.minusDays(4), 5, OrderStatus.COMPLETED);

        Map<Long, BigDecimal> average = consumptionService.dailyAverageOver(
                base.minusDays(27), base, recipes.findAllWithRelations());

        // 合計 1000g を「28 日」ではなく「2 営業日」で割る。
        // カレンダーで割ると 1 日 36g という現実離れした数字になり、
        // 「あと 50 日もつ」と言い出す。
        assertThat(average.get(cabbage.getId())).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("理論原価と原価率が出る（税込・税抜の両方）")
    void computes_theoretical_cost() {
        // キャベツ 1000g を 200円（税込8%）で仕入れた → 0.2 円/g
        fixture.recordPurchase(cabbage, 200, new BigDecimal("1000"), 8);
        // 1 品に 150g 使う → 原価 30 円。売価 1000 円なら原価率 3.0%
        registerRecipe(new BigDecimal("150"));

        RecipeCost cost = recipeService.costOf(okonomiyaki.getId());
        assertThat(cost.costIncludingTax()).isEqualByComparingTo("30.0");
        assertThat(cost.costRateIncludingTax()).isEqualByComparingTo("3.0");
        // 税抜のほうが原価も売価も小さくなるが、率はどちらも出ること
        assertThat(cost.costNet()).isNotNull();
        assertThat(cost.costRateNet()).isNotNull();
        assertThat(cost.isIncomplete()).isFalse();
    }

    @Test
    @DisplayName("一度も仕入れていない食材は原価に足さず、足りないと断る")
    void unknown_unit_cost_is_reported_not_guessed() {
        // 0 円として足すと原価率が実際より低く出て「思ったより儲かる」と誤解させる。
        registerRecipe(new BigDecimal("150"));

        RecipeCost cost = recipeService.costOf(okonomiyaki.getId());
        assertThat(cost.isIncomplete()).isTrue();
        assertThat(cost.unknownCostCount()).isEqualTo(1);
        assertThat(cost.costIncludingTax()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("材料を外すと原価から消える（レシピは履歴ではなく設定）")
    void removing_a_line_updates_the_cost() {
        fixture.recordPurchase(cabbage, 200, new BigDecimal("1000"), 8);
        registerRecipe(new BigDecimal("150"));

        List<jp.komeko.order.inventory.domain.RecipeLine> lines =
                recipeService.linesOf(okonomiyaki.getId());
        assertThat(lines).hasSize(1);

        recipeService.removeLine(lines.get(0).getId());

        RecipeCost cost = recipeService.costOf(okonomiyaki.getId());
        assertThat(cost.isEmpty()).isTrue();
        assertThat(cost.costIncludingTax()).isNull();
    }
}
