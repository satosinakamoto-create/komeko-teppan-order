package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.TaxCalculator;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.RecipeLine;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.repository.RecipeLineRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.service.ShopSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * レシピの登録と、理論原価・原価率の計算。
 *
 * <p><b>これが前職のエクセルの原価表そのものです。</b>
 * 商品名・材料・分量・原価・原価率が 1 枚に並び、
 * 食材を仕入れ直すたびに原価が勝手に更新される、という違いだけがあります。
 *
 * <p><b>単価は在庫の層から借ります。</b>
 * 「最新の仕入価格を使う」「手動で固定できる」という判断は
 * {@link StockService} が持っているので、ここでは持ちません。
 * 同じ判断を 2 箇所に書くと、片方だけ直したときに数字が食い違います。
 */
@Service
public class RecipeService {

    private static final Logger log = LoggerFactory.getLogger(RecipeService.class);

    private final RecipeLineRepository recipes;
    private final IngredientRepository ingredients;
    private final MenuItemRepository menuItems;
    private final StockService stockService;
    private final ShopSettingService shopSettings;

    public RecipeService(RecipeLineRepository recipes,
                         IngredientRepository ingredients,
                         MenuItemRepository menuItems,
                         StockService stockService,
                         ShopSettingService shopSettings) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.menuItems = menuItems;
        this.stockService = stockService;
        this.shopSettings = shopSettings;
    }

    // ========================================================================
    //  原価表
    // ========================================================================

    /**
     * すべての商品の理論原価と原価率。
     *
     * <p>レシピ未登録の商品も<b>行として出します</b>。
     * 一覧から消してしまうと、登録し忘れていることに気づけません。
     * 「まだ登録していない」ことが見えているのが大事です。
     */
    @Transactional(readOnly = true)
    public List<RecipeCost> costTable() {
        List<MenuItem> allItems = menuItems.findAllForAdmin();
        Map<Long, List<RecipeLine>> byMenuItem = groupRecipes();
        Map<Long, StockLevel> levels = levelsByIngredient();
        int taxRate = shopSettings.currentReadOnly().getTaxRatePercent();

        List<RecipeCost> result = new ArrayList<>(allItems.size());
        for (MenuItem item : allItems) {
            result.add(costOf(item, byMenuItem.getOrDefault(item.getId(), List.of()), levels, taxRate));
        }
        return result;
    }

    /** 1 商品ぶんの原価。編集画面でその場に出す。 */
    @Transactional(readOnly = true)
    public RecipeCost costOf(Long menuItemId) {
        MenuItem item = menuItems.findById(menuItemId).orElse(null);
        if (item == null) {
            return null;
        }
        return costOf(item, recipes.findByMenuItem(menuItemId), levelsByIngredient(),
                shopSettings.currentReadOnly().getTaxRatePercent());
    }

    /**
     * 理論原価を組み立てる。
     *
     * <p><b>単価が分からない食材は原価に足さず、数だけ数えます。</b>
     * 0 円として足してしまうと原価率が実際より低く出て、
     * 「思ったより儲かる」という誤解を生みます。
     * 足りないことが見えているほうが、静かに間違うよりずっとよい。
     */
    private RecipeCost costOf(MenuItem item, List<RecipeLine> lines,
                              Map<Long, StockLevel> levels, int taxRatePercent) {
        BigDecimal costIncludingTax = BigDecimal.ZERO;
        BigDecimal costNet = BigDecimal.ZERO;
        int unknown = 0;
        List<RecipeCost.LineCost> lineCosts = new ArrayList<>(lines.size());

        for (RecipeLine line : lines) {
            StockLevel level = levels.get(line.getIngredient().getId());
            BigDecimal unitIncludingTax = level != null ? level.unitCostIncludingTax() : null;
            BigDecimal unitNet = level != null ? level.unitCostNet() : null;

            if (unitIncludingTax == null || unitNet == null) {
                unknown++;
                lineCosts.add(new RecipeCost.LineCost(line, null, null));
                continue;
            }
            BigDecimal lineCost = line.costOf(unitIncludingTax);
            costIncludingTax = costIncludingTax.add(lineCost);
            costNet = costNet.add(line.costOf(unitNet));
            lineCosts.add(new RecipeCost.LineCost(line, unitIncludingTax, lineCost));
        }

        int priceIncludingTax = item.getPrice();
        // 売価は税込で持っているのが既存の規約。税抜は既存の TaxCalculator で割り戻す
        // （自前で計算しない、が規約）。税率もハードコードせず店舗設定から取る。
        int priceNet = TaxCalculator.netAmount(priceIncludingTax, taxRatePercent);

        return new RecipeCost(item, lineCosts,
                lines.isEmpty() ? null : costIncludingTax,
                lines.isEmpty() ? null : costNet,
                priceIncludingTax, priceNet, unknown);
    }

    /** 食材 id → 在庫と単価。原価計算のたびに在庫を数え直さないよう 1 回だけ引く。 */
    private Map<Long, StockLevel> levelsByIngredient() {
        Map<Long, StockLevel> map = new HashMap<>();
        for (StockLevel level : stockService.currentLevels()) {
            map.put(level.ingredient().getId(), level);
        }
        return map;
    }

    private Map<Long, List<RecipeLine>> groupRecipes() {
        Map<Long, List<RecipeLine>> map = new HashMap<>();
        for (RecipeLine line : recipes.findAllWithRelations()) {
            map.computeIfAbsent(line.getMenuItem().getId(), k -> new ArrayList<>()).add(line);
        }
        return map;
    }

    // ========================================================================
    //  レシピの編集
    // ========================================================================

    @Transactional(readOnly = true)
    public List<RecipeLine> linesOf(Long menuItemId) {
        return recipes.findByMenuItem(menuItemId);
    }

    @Transactional(readOnly = true)
    public MenuItem findMenuItem(Long menuItemId) {
        return menuItems.findById(menuItemId).orElse(null);
    }

    /** 材料を 1 行足す。 */
    @Transactional
    public void addLine(Long menuItemId, Long ingredientId, BigDecimal qtyPerItem, String memo) {
        MenuItem item = menuItems.findById(menuItemId).orElseThrow();
        Ingredient ingredient = ingredients.findById(ingredientId).orElseThrow();

        RecipeLine line = new RecipeLine(item, ingredient, qtyPerItem);
        line.setMemo(memo);
        recipes.save(line);
        log.info("レシピに材料を足しました: {} ← {} {} {}",
                item.getName(), ingredient.getName(), qtyPerItem, ingredient.getUnit().getSymbol());
    }

    /**
     * 分量を直す。
     *
     * <p><b>メモは、渡されなかったら残します。</b>
     * 画面の「直す」ボタンは分量だけを送ってきます。そこで null を
     * そのまま代入すると、分量を直すたびに書いてあったメモが黙って消えます
     * （2026-08-31 のUI監査で見つかった。「千切り」「生地に混ぜる」が
     * 分量の微調整のたびに失われていた）。
     * 消したいのではなく送っていないだけなので、null は「変更なし」と読みます。
     */
    @Transactional
    public void updateLine(Long lineId, BigDecimal qtyPerItem, String memo) {
        recipes.findById(lineId).ifPresent(line -> {
            line.setQtyPerItem(qtyPerItem);
            if (memo != null) {
                line.setMemo(memo);
            }
        });
    }

    /**
     * 材料を 1 行外す。
     *
     * <p><b>ここは物理削除でよいところです。</b>レシピは履歴ではなく「いまの設定」で、
     * 過去の注文は品名も価格も自分でスナップショットを持っています。
     * レシピ行を消しても、去年の売上も、そのとき計算された在庫も変わりません。
     * 消してはいけないもの（仕入れ・棚卸し・食材そのもの）とは性質が違います。
     */
    @Transactional
    public void removeLine(Long lineId) {
        recipes.findById(lineId).ifPresent(line -> {
            log.info("レシピから材料を外しました: {} ← {}",
                    line.getMenuItem().getName(), line.getIngredient().getName());
            recipes.delete(line);
        });
    }

    // ========================================================================
    //  登録漏れの警告
    // ========================================================================

    /**
     * レシピが未登録の商品。
     *
     * <p><b>この一覧が、予測の甘さに対する唯一の保険です。</b>
     * 登録漏れは黙って効き、しかも米粉のように多くのメニューにまたがる食材ほど
     * 大きく効きます。20 品中 15 品しか登録していなければ消費の 25% が漏れ、
     * 「まだある」と言われて発注しない事故につながります。
     *
     * <p>お客さんに出していない商品（非表示）は数えません。
     * 出していない料理は作られないので、在庫も減らないからです。
     */
    @Transactional(readOnly = true)
    public List<MenuItem> menuItemsWithoutRecipe() {
        List<Long> withRecipe = recipes.findMenuItemIdsWithRecipe();
        List<MenuItem> missing = new ArrayList<>();
        for (MenuItem item : menuItems.findAllForAdmin()) {
            if (item.isVisible() && !withRecipe.contains(item.getId())) {
                missing.add(item);
            }
        }
        return missing;
    }

    /** 選択肢に出す食材（使っているものだけ）。 */
    @Transactional(readOnly = true)
    public List<Ingredient> selectableIngredients() {
        return ingredients.findByActiveTrueOrderBySortOrderAscNameAsc();
    }
}
