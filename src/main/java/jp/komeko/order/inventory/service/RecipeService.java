package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.TaxCalculator;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.RecipeLine;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.domain.RecipeOtherCost;
import jp.komeko.order.inventory.repository.RecipeLineRepository;
import jp.komeko.order.inventory.repository.RecipeOtherCostRepository;
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
    private final RecipeOtherCostRepository otherCosts;
    private final IngredientRepository ingredients;
    private final MenuItemRepository menuItems;
    private final StockService stockService;
    private final ShopSettingService shopSettings;

    public RecipeService(RecipeLineRepository recipes,
                         RecipeOtherCostRepository otherCosts,
                         IngredientRepository ingredients,
                         MenuItemRepository menuItems,
                         StockService stockService,
                         ShopSettingService shopSettings) {
        this.recipes = recipes;
        this.otherCosts = otherCosts;
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

        // 商品ごとに引くと 80 商品で 80 往復になる。1 回読んで Java で配る。
        Map<Long, Integer> otherByMenuItem = new HashMap<>();
        for (RecipeOtherCost other : otherCosts.findAll()) {
            otherByMenuItem.put(other.getMenuItemId(), other.getAmountIncludingTax());
        }

        List<RecipeCost> result = new ArrayList<>(allItems.size());
        for (MenuItem item : allItems) {
            result.add(costOf(item, byMenuItem.getOrDefault(item.getId(), List.of()),
                    levels, taxRate, otherByMenuItem.get(item.getId())));
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
                shopSettings.currentReadOnly().getTaxRatePercent(),
                otherCosts.findByMenuItemId(menuItemId)
                        .map(RecipeOtherCost::getAmountIncludingTax).orElse(null));
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
                              Map<Long, StockLevel> levels, int taxRatePercent,
                              Integer otherCostIncludingTax) {
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

        // その他材料費はレシピ行と足し合わせる。
        // ★ unknownCostCount には数えない。あちらは「単価が分からない食材」の数で、
        //   人が入れた確定値とは種類が違う。混ぜると警告の意味が濁る。
        if (otherCostIncludingTax != null) {
            costIncludingTax = costIncludingTax.add(BigDecimal.valueOf(otherCostIncludingTax));
            // 税抜は自前で計算せず既存の TaxCalculator に割り戻させる（規約）
            costNet = costNet.add(BigDecimal.valueOf(
                    TaxCalculator.netAmount(otherCostIncludingTax, taxRatePercent)));
        }

        int priceIncludingTax = item.getPrice();
        // 売価は税込で持っているのが既存の規約。税抜は既存の TaxCalculator で割り戻す
        // （自前で計算しない、が規約）。税率もハードコードせず店舗設定から取る。
        int priceNet = TaxCalculator.netAmount(priceIncludingTax, taxRatePercent);

        // ★ 原価が null になるのは「レシピ行が 0 件 かつ その他材料費も無い」ときだけ。
        //   0 円と出すと「原価がかからない」という意味になってしまうので、
        //   分からないときは分からないと出す（原価率の判断と同じ）。
        boolean nothingRegistered = lines.isEmpty() && otherCostIncludingTax == null;

        return new RecipeCost(item, lineCosts,
                nothingRegistered ? null : costIncludingTax,
                nothingRegistered ? null : costNet,
                priceIncludingTax, priceNet, unknown, otherCostIncludingTax);
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

    /**
     * 商品が属するカテゴリの名前。無ければ空文字。
     *
     * <p><b>なぜ画面で {@code menuItem.category.name} と書かないか。</b>
     * {@code open-in-view: false} なので<b>描画時には DB 接続がありません</b>。
     * カテゴリは LAZY なので、画面側で辿ると
     * {@code LazyInitializationException} でテンプレートごと落ちます
     * （2026-09-14 に見出しへカテゴリを足したとき、実際に落とした）。
     *
     * <p>必要な関連は、この {@code @Transactional} の中で読み終えてから返す——
     * CLAUDE.md の決まりどおりの形。
     */
    @Transactional(readOnly = true)
    public String categoryNameOf(Long menuItemId) {
        MenuItem item = menuItems.findById(menuItemId).orElse(null);
        if (item == null || item.getCategory() == null) {
            return "";
        }
        return item.getCategory().getName();
    }

    /**
     * 商品名だけを文字列で返す。無ければ {@code null}。
     *
     * <p>食材を追加する画面で「『肉玉米粉そば』のレシピ編集から来ました」と
     * 案内するために使います（設計 ト04b 838:9207・2026-09-14）。
     *
     * <p>もとは戻り先のパス（{@code /inventory/recipes/12}）しか持っていなかったので、
     * 画面は「レシピ編集から来ています」としか言えませんでした。
     * レシピを 2 つ並行で直していると、どちらへ戻るのか分かりません。
     *
     * <p>{@link #categoryNameOf(Long)} と同じ理由で、<b>ここで文字列にしてから渡します</b>。
     * 画面で {@code menuItem.name} と辿ると {@code open-in-view: false} のため
     * 描画時に DB 接続が無く落ちます。
     *
     * <p>見つからないときに空文字ではなく {@code null} を返すのは、
     * 画面側で「案内そのものを出さない」と「名前が空の案内を出す」を
     * 区別できるようにするため。消された商品の戻り道で
     * 「『』のレシピ編集から来ました」と出したら、ただの壊れた表示になります。
     */
    @Transactional(readOnly = true)
    public String menuItemNameOf(Long menuItemId) {
        if (menuItemId == null) {
            return null;
        }
        return menuItems.findById(menuItemId).map(MenuItem::getName).orElse(null);
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

    /**
     * その商品の「その他材料費」を入れる。商品 1 つにつき 1 件で、入れ直すと上書き。
     *
     * <p><b>0 円は行ごと消します。</b>残してしまうと「検討した結果 0 円だった」と
     * 「まだ入れていない」が画面から区別できなくなります。
     * 削除した食材の分類で「その他」を既定にしなかったのと同じ理由です。
     *
     * <p><b>マイナスは受け付けません。</b>原価から材料費が引かれて原価率が
     * 実際より低く出ます。「思ったより儲かる」という誤解は静かに効くぶん質が悪い。
     *
     * @param amountIncludingTax 円・税込。0 なら削除、負数なら {@link IllegalArgumentException}
     * @param memo               「ソース・青のり・かつお節」など。任意
     */
    @Transactional
    public void setOtherCost(Long menuItemId, int amountIncludingTax, String memo) {
        if (amountIncludingTax < 0) {
            throw new IllegalArgumentException(
                    "その他材料費にマイナスは入れられません（原価率が実際より低く出ます）");
        }
        if (amountIncludingTax == 0) {
            clearOtherCost(menuItemId);
            return;
        }
        RecipeOtherCost existing = otherCosts.findByMenuItemId(menuItemId).orElse(null);
        if (existing == null) {
            otherCosts.save(new RecipeOtherCost(menuItemId, amountIncludingTax, memo));
        } else {
            existing.setAmountIncludingTax(amountIncludingTax);
            existing.setMemo(memo);
        }
        log.info("その他材料費を設定しました: menuItemId={} {} 円", menuItemId, amountIncludingTax);
    }

    /** その他材料費を外す。入っていなければ何もしない。 */
    @Transactional
    public void clearOtherCost(Long menuItemId) {
        otherCosts.findByMenuItemId(menuItemId).ifPresent(found -> {
            otherCosts.delete(found);
            log.info("その他材料費を外しました: menuItemId={}", menuItemId);
        });
    }

    /** その商品のその他材料費。入っていなければ null。画面のフォーム初期値用。 */
    @Transactional(readOnly = true)
    public RecipeOtherCost otherCostOf(Long menuItemId) {
        return otherCosts.findByMenuItemId(menuItemId).orElse(null);
    }

    /**
     * 取り込み画面の「登録済みの商品から選ぶ」に出す商品。
     *
     * <p>掲載を止めている商品も出します。<b>取り込むのは過去のレシピ</b>なので、
     * いま売っていない品のレシピを入れ直すことがあるためです
     * （原価表も同じ理由で全商品を出しています）。
     */
    @Transactional(readOnly = true)
    public List<MenuItem> importableMenuItems() {
        return menuItems.findAllForAdmin();
    }

    /**
     * 商品名から商品を探す（取り込みの自動照合用）。
     *
     * <p>照合は {@code AliasText.normalize} を通します。生の文字列で引くと
     * 「肉玉米粉そば」と「肉玉米粉そば　」が別物になり、
     * ノートの書き方ひとつで照合が外れます。
     *
     * <p><b>複数一致したときは null を返します。</b>どれか 1 つを勝手に選ぶと、
     * 人は「一致した」と思ったまま別の商品にレシピを入れてしまいます。
     * 決められないときは決めずに、画面で選ばせるのが正しい。
     */
    @Transactional(readOnly = true)
    public MenuItem findByNameForImport(String rawName) {
        String needle = jp.komeko.order.inventory.domain.AliasText.normalize(rawName);
        if (needle == null) {
            return null;
        }
        MenuItem found = null;
        for (MenuItem item : menuItems.findAllForAdmin()) {
            String candidate = jp.komeko.order.inventory.domain.AliasText.normalize(item.getName());
            if (needle.equals(candidate)) {
                if (found != null) {
                    return null;   // 複数一致。勝手に選ばない
                }
                found = item;
            }
        }
        return found;
    }

    /** 選択肢に出す食材（使っているものだけ）。 */
    @Transactional(readOnly = true)
    public List<Ingredient> selectableIngredients() {
        return ingredients.findByActiveTrueOrderBySortOrderAscNameAsc();
    }
}
