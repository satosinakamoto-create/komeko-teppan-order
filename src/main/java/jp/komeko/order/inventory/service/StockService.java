package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.repository.PurchaseLineRepository;
import jp.komeko.order.inventory.repository.StocktakeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在庫を計算する係。
 *
 * <p><b>在庫はどこにも保存していません。</b>毎回、記録から組み立て直します。
 *
 * <pre>
 * 現在庫 = 直近の棚卸しの実測量        ※棚卸しが 1 度も無ければ 0 から
 *        + その後の入庫                （仕入れ明細の換算量）
 *        + その後の増減                （廃棄・まかない。ふつうは負）
 * </pre>
 *
 * <p>銀行の口座残高と同じ考え方です。残高という値を持たず、入出金の記録から出す。
 * こうしておくと、あとから過去の記録を直しても、その後の残高が自動でつじつまを取ります。
 * 値を保存してしまうと「計算した値」と「保存した値」の 2 つが生まれ、
 * 食い違ったときにどちらが正しいのか誰にも分からなくなります。
 *
 * <p><b>間違えやすいところ: 「直近の棚卸し」は "対象時点以前の" 直近</b><br>
 * 今日棚卸しを済ませた状態で「先週の在庫は？」と聞かれたとき、
 * 使うのは<b>先週以前の</b>棚卸しです。今日の実測を起点にすると、
 * 過去に向かって計算することになり答えが逆に動きます。
 * ここはテストで固定してあります。
 *
 * <p><b>棚卸しと同じ日の仕入れは数えません。</b><br>
 * 棚卸しは営業が終わってから、その日の仕入れを棚に入れ終えたあとに行うものなので、
 * 同じ日の仕入れは実測値にすでに含まれている、という前提を置いています。
 * 増減（廃棄・まかない）だけは記録した時刻が分かるので、
 * 棚卸しより後に記録されたものは同じ日でも数えます。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final IngredientRepository ingredients;
    private final StocktakeRepository stocktakes;
    private final PurchaseLineRepository purchaseLines;
    private final TaxRuleService taxRules;
    private final Clock clock;

    public StockService(IngredientRepository ingredients,
                        StocktakeRepository stocktakes,
                        PurchaseLineRepository purchaseLines,
                        TaxRuleService taxRules,
                        Clock clock) {
        this.ingredients = ingredients;
        this.stocktakes = stocktakes;
        this.purchaseLines = purchaseLines;
        this.taxRules = taxRules;
        this.clock = clock;
    }

    // ========================================================================
    //  在庫を出す
    // ========================================================================

    /** いまの在庫。画面の既定はこれ。 */
    @Transactional(readOnly = true)
    public List<StockLevel> currentLevels() {
        return levelsAsOf(LocalDate.now(clock));
    }

    /**
     * 指定した日の終わり時点の在庫を、使っている食材ぶんまとめて出す。
     *
     * <p>食材ごとに問い合わせを分けず、必要な記録を 3 回の読み取りでまとめて取り、
     * あとは Java で組み立てます。食材が 50 あっても往復は 3 回のままです。
     *
     * @param asOf この日の終わりの時点で計算する
     */
    @Transactional(readOnly = true)
    public List<StockLevel> levelsAsOf(LocalDate asOf) {
        List<Ingredient> targets = ingredients.findByActiveTrueOrderBySortOrderAscNameAsc();
        if (targets.isEmpty()) {
            return List.of();
        }

        // 棚卸しの記録は 1 度だけ読み、起点の判定と増減の集計の 2 回なめる。
        List<Stocktake> events = stocktakes.findUpTo(asOf);
        Map<Long, Baseline> baselines = baselinesFrom(events);
        Map<Long, BigDecimal> adjustments = adjustmentsAfterBaseline(events, baselines);
        StockInflow inflow = inflowAfterBaseline(asOf, baselines);

        List<StockLevel> result = new ArrayList<>(targets.size());
        for (Ingredient ingredient : targets) {
            Long id = ingredient.getId();
            Baseline baseline = baselines.get(id);

            BigDecimal base = baseline != null ? baseline.quantity() : BigDecimal.ZERO;
            BigDecimal received = inflow.received().getOrDefault(id, BigDecimal.ZERO);
            BigDecimal adjusted = adjustments.getOrDefault(id, BigDecimal.ZERO);

            // 消費（注文 × レシピ）は在庫の層では 0。レシピの層（Step 3）で埋まる。
            // 0 でも計算は成り立つ。使った分は棚卸しの実測値が吸収するため。
            BigDecimal consumed = BigDecimal.ZERO;

            BigDecimal quantity = base.add(received).subtract(consumed).add(adjusted);

            UnitCost cost = unitCostOf(ingredient, inflow.latestLine().get(id));

            result.add(new StockLevel(
                    ingredient,
                    quantity,
                    baseline != null ? baseline.takenOn() : null,
                    base,
                    received,
                    consumed,
                    adjusted,
                    cost.includingTax(),
                    cost.net(),
                    cost.overridden(),
                    null,             // あと何営業日もつか。レシピの層（Step 3）で埋まる
                    null));
        }
        return result;
    }

    /** 1 つの食材だけ。詳細画面用。 */
    @Transactional(readOnly = true)
    public StockLevel levelOf(Long ingredientId) {
        for (StockLevel level : currentLevels()) {
            if (level.ingredient().getId().equals(ingredientId)) {
                return level;
            }
        }
        return null;
    }

    // ========================================================================
    //  記録する
    // ========================================================================

    /**
     * 棚卸し（実測リセット）を記録する。ここが以後の在庫計算の起点になる。
     */
    @Transactional
    public Stocktake recordStocktake(Long ingredientId, LocalDate takenOn,
                                     BigDecimal measured, String memo, Long staffId) {
        Ingredient ingredient = ingredients.findById(ingredientId).orElseThrow();
        Stocktake stocktake = new Stocktake(ingredient, takenOn, StocktakeType.RESET,
                measured, StocktakeReason.STOCKTAKE, LocalDateTime.now(clock));
        stocktake.setMemo(memo);
        stocktake.setCreatedBy(staffId);
        Stocktake saved = stocktakes.save(stocktake);
        log.info("棚卸しを記録しました: 食材={} {}={} ({})",
                ingredient.getName(), takenOn, measured, ingredient.getUnit().getSymbol());
        return saved;
    }

    /**
     * 増減（廃棄・まかないなど）を記録する。
     *
     * <p>減らすときは負の数で渡します。画面では「減らす量」を正の数で入力してもらい、
     * コントローラで符号を付けます。<b>マイナスを人に入力させない</b>ほうが事故が減ります。
     */
    @Transactional
    public Stocktake recordAdjustment(Long ingredientId, LocalDate takenOn, BigDecimal delta,
                                      StocktakeReason reason, String memo, Long staffId) {
        Ingredient ingredient = ingredients.findById(ingredientId).orElseThrow();
        Stocktake stocktake = new Stocktake(ingredient, takenOn, StocktakeType.ADJUST,
                delta, reason, LocalDateTime.now(clock));
        stocktake.setMemo(memo);
        stocktake.setCreatedBy(staffId);
        Stocktake saved = stocktakes.save(stocktake);
        log.info("在庫の増減を記録しました: 食材={} {} {} 理由={}",
                ingredient.getName(), takenOn, delta, reason.getLabel());
        return saved;
    }

    /** ある食材の記録の履歴（新しい順）。 */
    @Transactional(readOnly = true)
    public List<Stocktake> historyOf(Long ingredientId) {
        return stocktakes.findHistory(ingredientId);
    }

    /** 期間の記録。棚卸し画面の「最近の記録」用。 */
    @Transactional(readOnly = true)
    public List<Stocktake> recentRecords(LocalDate from, LocalDate to) {
        return stocktakes.findForPeriod(from, to);
    }

    // ========================================================================
    //  組み立ての部品
    // ========================================================================

    /**
     * 食材ごとの起点（対象時点以前で最も新しい棚卸し）。
     *
     * <p>記録は古い順に並んで届くので、RESET を見つけるたびに上書きすれば
     * 最後に残るのが「直近」になります。同じ日に 2 回棚卸ししていたら、
     * あとから記録したほうが残ります（id の昇順で並べているため）。
     */
    private Map<Long, Baseline> baselinesFrom(List<Stocktake> events) {
        Map<Long, Baseline> baselines = new HashMap<>();
        for (Stocktake event : events) {
            if (event.getType() == StocktakeType.RESET) {
                baselines.put(event.getIngredient().getId(),
                        new Baseline(event.getTakenOn(), event.getQuantity(), event.getId()));
            }
        }
        return baselines;
    }

    /**
     * 起点より後の増減の合計。
     *
     * <p>同じ日の増減は、<b>棚卸しより後に記録されたものだけ</b>数えます。
     * 「数えたあとで 3 パック捨てた」を落とさないためです。
     * 日付だけで切ると、この 3 パックが消えて在庫が多めに出ます。
     */
    private Map<Long, BigDecimal> adjustmentsAfterBaseline(List<Stocktake> events, Map<Long, Baseline> baselines) {
        Map<Long, BigDecimal> sums = new HashMap<>();
        for (Stocktake event : events) {
            if (event.getType() != StocktakeType.ADJUST) {
                continue;
            }
            Long id = event.getIngredient().getId();
            Baseline baseline = baselines.get(id);
            if (baseline != null && !isAfterBaseline(event, baseline)) {
                continue;
            }
            sums.merge(id, event.getQuantity(), BigDecimal::add);
        }
        return sums;
    }

    /**
     * その増減が、起点の棚卸しより<b>あとに記録されたもの</b>か。
     *
     * <p>同じ日のときは<b>採番された順（id）</b>で決めます。時刻ではありません。
     * 記録した時刻を使うと、同じ操作の中で続けて保存したときに
     * ミリ秒までまったく同じ値になり、順序が付きません
     * （実際にテストがこれで落ちました）。id は必ず増えるので、迷いようがありません。
     */
    private boolean isAfterBaseline(Stocktake event, Baseline baseline) {
        if (event.getTakenOn().isAfter(baseline.takenOn())) {
            return true;
        }
        if (event.getTakenOn().isBefore(baseline.takenOn())) {
            return false;
        }
        return event.getId() > baseline.id();
    }

    /**
     * 起点より後の入庫合計と、食材ごとの最新の仕入れ明細。
     *
     * <p>同じ読み取りから 2 つ取り出しているのは、往復を減らすためです。
     * 入庫は起点より後だけを数えますが、単価は<b>起点より前の仕入れでも</b>
     * 最新なら使います（しばらく買っていない食材の単価が消えては困る）。
     */
    private StockInflow inflowAfterBaseline(LocalDate asOf, Map<Long, Baseline> baselines) {
        Map<Long, BigDecimal> received = new HashMap<>();
        Map<Long, PurchaseLine> latest = new HashMap<>();

        for (PurchaseLine line : purchaseLines.findStockFeedingLinesUpTo(asOf)) {
            Long id = line.getIngredient().getId();

            // 量を教わっていない行はここで落とす。
            // 食材には紐付いているが数量が分からない行は日常的に発生するので、
            // これは異常系ではなく通常の分岐。
            if (!line.feedsStock()) {
                continue;
            }
            latest.put(id, line);   // 古い順に届くので、最後に残るのが最新

            Baseline baseline = baselines.get(id);
            LocalDate purchasedOn = line.getPurchase().getPurchasedOn();
            if (baseline != null && !purchasedOn.isAfter(baseline.takenOn())) {
                continue;   // 棚卸しと同じ日以前の仕入れは、実測値に織り込み済みとみなす
            }
            received.merge(id, line.getStockQty(), BigDecimal::add);
        }
        return new StockInflow(received, latest);
    }

    /**
     * 食材 1 単位あたりの単価。手動の上書きがあればそれを、なければ最新の仕入れから。
     *
     * <p>最新の仕入価格を使うので、昨日の値上がりが今日の原価率にそのまま出ます。
     * 移動平均のような会計的に厳密な方式は採りません。この規模ではやりすぎで、
     * 「いま仕入れたらいくらか」のほうが現場の判断に近いためです。
     */
    private UnitCost unitCostOf(Ingredient ingredient, PurchaseLine latestLine) {
        if (ingredient.getCostOverride() != null) {
            BigDecimal includingTax = ingredient.getCostOverride();
            // 上書きは税込で入れてもらう。税抜は税率で割り戻す。
            // 実際に仕入れた行があればその行に印字されていた税率を使い、
            // まだ 1 度も仕入れていなければマスタに聞く（税率をここに書かない、が規約）。
            int taxRate = latestLine != null
                    ? latestLine.getTaxRatePercent()
                    : taxRules.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, LocalDate.now(clock));
            BigDecimal net = includingTax
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(100 + taxRate), 4, RoundingMode.HALF_UP);
            return new UnitCost(includingTax, net, true);
        }
        if (latestLine == null) {
            return new UnitCost(null, null, false);
        }
        return new UnitCost(latestLine.unitCostIncludingTax(), latestLine.unitCostNet(), false);
    }

    /** 起点になった棚卸し。{@code id} は同じ日の記録の前後を決めるために持つ。 */
    private record Baseline(LocalDate takenOn, BigDecimal quantity, Long id) {
    }

    /** 入庫の合計と、単価の根拠になる最新の仕入れ明細。 */
    private record StockInflow(Map<Long, BigDecimal> received, Map<Long, PurchaseLine> latestLine) {
    }

    /** 食材 1 単位あたりの単価（税込・税抜）と、手動上書きかどうか。 */
    private record UnitCost(BigDecimal includingTax, BigDecimal net, boolean overridden) {
    }
}
