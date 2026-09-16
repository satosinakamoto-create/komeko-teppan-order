package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * その他材料費（2026-09-16、店主の判断）。
 *
 * <p><b>なぜ作ったか。</b>「レシピ原価表で食材を g 数しか編集できない。
 * 食材入れずに原価だけ入れて保存できないの？」という指摘から。
 * 原価を知りたいだけの商品にも全食材の g 数登録を強いていました。
 * 油・塩・ソースのように量を測らないものもあります。
 *
 * <p><b>この設計が解決するのは「g 数を任意にすること」だけです。</b>
 * g 数の入力が楽になるわけではありません。在庫を自動で減らしたい商品には
 * 結局 g 数が要ります。設計は {@code docs/レシピのその他材料費-設計-20260916.md}。
 *
 * <p><b>いちばん大事なのは {@link #theOtherCostNeverTouchesStock()} です。</b>
 * この設計は「金額行はレシピ行ではないので、消費計算から原理的に見えない」
 * ことを前提に、除外の処理を 1 行も書いていません。
 * その前提が崩れたらここで気づけるようにしてあります。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("その他材料費")
class RecipeOtherCostTest {

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private StockService stockService;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private InventoryTestFixture fixture;

    @Autowired
    private jp.komeko.order.service.MenuService menuService;

    private MenuItem takoyaki;
    private Ingredient cabbage;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        takoyaki = fixture.createMenuItem("その他材料費テスト用たこ焼き-" + suffix, 400);
        cabbage = ingredients.save(
                new Ingredient("その他材料費テスト用キャベツ-" + suffix, IngredientUnit.GRAM));
        // 1000 円で 1000g 仕入れる ＝ 1 円/g。端数で検証が濁らないようにする
        fixture.recordPurchase(cabbage, 1000, new BigDecimal("1000"), 8);
    }

    // ---------------------------------------------------------------- 1. 食材ゼロ

    /**
     * ★ これが店主の最初の要望そのものです。
     * 「食材入れずに原価だけ入れて保存できないの？」
     */
    @Test
    @DisplayName("★ 食材を 1 件も入れず、金額だけで原価と原価率が出る")
    void theCostAppearsWithNoIngredientsAtAll() {
        recipeService.setOtherCost(takoyaki.getId(), 180, "ソース・青のり・かつお節");

        RecipeCost cost = recipeService.costOf(takoyaki.getId());

        assertThat(cost.costIncludingTax())
                .as("レシピ行が 0 件でも、その他材料費があれば原価は出せる")
                .isEqualByComparingTo("180");
        // 180 / 400 = 45.0%
        assertThat(cost.costRateIncludingTax()).isEqualByComparingTo("45.0");
    }

    // ---------------------------------------------------------------- 2. 混在

    /**
     * ★ もう 1 つの要望。「主要な食材だけ g 数で入れて、残りはまとめて金額で置く」。
     */
    @Test
    @DisplayName("★ レシピ行と混ざったら合算される")
    void theOtherCostAddsOnTopOfTheRecipeLines() {
        recipeService.addLine(takoyaki.getId(), cabbage.getId(), new BigDecimal("50"), null);
        recipeService.setOtherCost(takoyaki.getId(), 40, "ソース・青のり");

        RecipeCost cost = recipeService.costOf(takoyaki.getId());

        // キャベツ 50g × 1 円/g = 50 円、＋ その他材料費 40 円
        assertThat(cost.costIncludingTax()).isEqualByComparingTo("90");
    }

    /**
     * 税抜も一緒に動くこと。税率は店舗設定から来るので、ここでは
     * 「税抜が税込より小さく、0 でない」ことだけを見ます。
     * 割り戻しの正しさは {@code TaxCalculator} 側のテストの担当です。
     */
    @Test
    @DisplayName("税抜の原価にもその他材料費が入る")
    void theNetCostIncludesTheOtherCostToo() {
        recipeService.setOtherCost(takoyaki.getId(), 180, null);

        RecipeCost cost = recipeService.costOf(takoyaki.getId());

        assertThat(cost.costNet()).isNotNull();
        assertThat(cost.costNet().signum()).as("税抜が 0 になっている").isPositive();
        assertThat(cost.costNet()).isLessThan(cost.costIncludingTax());
    }

    // ---------------------------------------------------------------- 3. 在庫（最重要）

    /**
     * ★ この設計の前提そのもの。
     *
     * <p>その他材料費は {@code recipe_line} ではないので、消費計算からは
     * 原理的に見えません。だから {@code ConsumptionService} にも
     * {@code StockService} にも除外の処理を 1 行も書いていません。
     * <b>前提が崩れたらここが落ちます。</b>
     */
    @Test
    @DisplayName("★ 在庫の消費計算には一切影響しない")
    void theOtherCostNeverTouchesStock() {
        stockService.recordStocktake(cabbage.getId(), LocalDate.now().minusDays(10),
                new BigDecimal("2000"), null, null);
        recipeService.addLine(takoyaki.getId(), cabbage.getId(), new BigDecimal("100"), null);
        fixture.placeOrder(takoyaki, LocalDate.now().minusDays(5), 6,
                jp.komeko.order.domain.OrderStatus.COMPLETED);

        BigDecimal before = stockOf(cabbage);

        recipeService.setOtherCost(takoyaki.getId(), 9999, "在庫に効いたら異常");

        assertThat(stockOf(cabbage))
                .as("その他材料費が在庫を動かしている（設計の前提が壊れている）")
                .isEqualByComparingTo(before);
    }

    private BigDecimal stockOf(Ingredient ingredient) {
        for (StockLevel level : stockService.currentLevels()) {
            if (level.ingredient().getId().equals(ingredient.getId())) {
                return level.quantity();
            }
        }
        throw new AssertionError("食材が在庫一覧に出てきませんでした");
    }

    // ---------------------------------------------------------------- 4. 値の約束

    /**
     * マイナスを許すと原価が減って原価率が嘘になります。
     * オプションの追加料金を 0 円以上に閉じているのと同じ判断です。
     */
    @Test
    @DisplayName("★ マイナスは保存できない（原価率が嘘になる）")
    void negativeAmountsAreRejected() {
        assertThatThrownBy(() -> recipeService.setOtherCost(takoyaki.getId(), -100, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 0 円を残すと「検討した結果 0 円」と「まだ入れていない」が区別できなくなります。
     * 未分類を null で表したのと同じ理由で、0 は行ごと消します。
     */
    @Test
    @DisplayName("★ 0 円は行ごと消える（「0 円」と「未入力」を混ぜない）")
    void zeroRemovesTheRow() {
        recipeService.setOtherCost(takoyaki.getId(), 180, null);
        assertThat(recipeService.costOf(takoyaki.getId()).otherCostIncludingTax()).isEqualTo(180);

        recipeService.setOtherCost(takoyaki.getId(), 0, null);

        RecipeCost cost = recipeService.costOf(takoyaki.getId());
        assertThat(cost.otherCostIncludingTax()).as("0 円の行が残っている").isNull();
        assertThat(cost.costIncludingTax())
                .as("レシピ行も その他材料費も無いのに原価が出ている").isNull();
    }

    @Test
    @DisplayName("★ 入れ直すと上書きされる（1 商品に 2 件できない）")
    void settingTwiceOverwrites() {
        recipeService.setOtherCost(takoyaki.getId(), 180, "1 回目");
        recipeService.setOtherCost(takoyaki.getId(), 250, "2 回目");

        RecipeCost cost = recipeService.costOf(takoyaki.getId());
        assertThat(cost.otherCostIncludingTax()).isEqualTo(250);
        assertThat(cost.costIncludingTax()).isEqualByComparingTo("250");
    }

    // ---------------------------------------------------------------- 5. 変えないもの

    /**
     * いままでどおり、何も無い商品の原価は null。
     * 0 円と表示すると「原価がかからない」という意味になってしまいます。
     */
    @Test
    @DisplayName("★ レシピ行も その他材料費も無ければ、原価は null のまま")
    void nothingRegisteredStillMeansUnknown() {
        RecipeCost cost = recipeService.costOf(takoyaki.getId());

        assertThat(cost.costIncludingTax()).isNull();
        assertThat(cost.costRateIncludingTax()).isNull();
    }

    /**
     * ★ 商品を消せなくならないこと。
     *
     * <p>{@code recipe_line} の外部キーには {@code ON DELETE CASCADE} が無く、
     * {@code MenuService.deleteItem} が<b>消す前に明示的にレシピ行を消して</b>います。
     * その他材料費も同じ外部キーを張るので、同じ後片付けをしないと
     * 「その他材料費を入れた商品だけ削除できない」という形で跳ね返ります。
     */
    @Test
    @DisplayName("★ その他材料費を入れた商品も削除できる（外部キーで詰まらない）")
    void theMenuItemCanStillBeDeleted() {
        recipeService.setOtherCost(takoyaki.getId(), 180, "消えること");

        menuService.deleteItem(takoyaki.getId());

        assertThat(recipeService.findMenuItem(takoyaki.getId()))
                .as("商品が消えていない").isNull();
    }

    /**
     * 在庫の「あと◯営業日が甘い」警告は、その他材料費では消えません。
     * 食材が紐づいていない＝売れても在庫が減らない＝予測は本当に甘いためです。
     * 原価が出ることと、在庫が読めることは別の話。
     */
    @Test
    @DisplayName("★ その他材料費だけでは「レシピ未登録」の警告は消えない")
    void theStockWarningStaysBecauseNoIngredientIsLinked() {
        recipeService.setOtherCost(takoyaki.getId(), 180, null);

        assertThat(recipeService.menuItemsWithoutRecipe())
                .as("食材が 1 つも紐づいていないのに、在庫の警告から外れている")
                .extracting(MenuItem::getId)
                .contains(takoyaki.getId());
    }
}
