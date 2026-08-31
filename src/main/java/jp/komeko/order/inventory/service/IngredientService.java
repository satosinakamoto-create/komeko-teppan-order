package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.AliasText;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.domain.ItemAlias;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.repository.ItemAliasRepository;
import jp.komeko.order.inventory.repository.PurchaseLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 食材マスタと「入り数の記憶」の世話をする係。
 *
 * <p><b>ここの仕事は、レシートの文字を食材につなぐことです。</b>
 * レシートには「エリンギ 120」としか書かれていません。何グラムかは印字されていない。
 * 人は袋を見れば分かりますが、システムには分かりません。
 *
 * <p>そこで<b>1 回だけ教えてもらい、以後は自動</b>にします。
 * 毎回やる作業は続きませんが、1 回だけなら続くからです。
 * これが {@link ItemAlias} で、このクラスがその読み書きを受け持ちます。
 */
@Service
public class IngredientService {

    private static final Logger log = LoggerFactory.getLogger(IngredientService.class);

    private final IngredientRepository ingredients;
    private final ItemAliasRepository aliases;
    private final PurchaseLineRepository purchaseLines;
    private final jp.komeko.order.inventory.repository.RecipeLineRepository recipeLines;
    private final Clock clock;

    public IngredientService(IngredientRepository ingredients,
                             ItemAliasRepository aliases,
                             PurchaseLineRepository purchaseLines,
                             jp.komeko.order.inventory.repository.RecipeLineRepository recipeLines,
                             Clock clock) {
        this.ingredients = ingredients;
        this.aliases = aliases;
        this.purchaseLines = purchaseLines;
        this.recipeLines = recipeLines;
        this.clock = clock;
    }

    // ========================================================================
    //  食材マスタ
    // ========================================================================

    /** 使っている食材（並び順）。 */
    @Transactional(readOnly = true)
    public List<Ingredient> activeIngredients() {
        return ingredients.findByActiveTrueOrderBySortOrderAscNameAsc();
    }

    /** 使わなくなったものも含めた全件。 */
    @Transactional(readOnly = true)
    public List<Ingredient> allIngredients() {
        return ingredients.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Ingredient find(Long id) {
        return ingredients.findById(id).orElse(null);
    }

    /** 同じ名前の食材がすでにあるか。登録前の重複チェック。 */
    @Transactional(readOnly = true)
    public boolean nameTaken(String name, Long excludeId) {
        Optional<Ingredient> found = ingredients.findByName(name);
        return found.isPresent() && !found.get().getId().equals(excludeId);
    }

    @Transactional
    public Ingredient create(String name, IngredientUnit unit, BigDecimal lowThreshold,
                             BigDecimal costOverride, String memo) {
        Ingredient ingredient = new Ingredient(name, unit);
        ingredient.setLowThresholdQty(lowThreshold);
        ingredient.setCostOverride(normalizeCostOverride(costOverride));
        ingredient.setMemo(memo);
        Ingredient saved = ingredients.save(ingredient);
        log.info("食材を登録しました: {} ({})", name, unit.getSymbol());
        return saved;
    }

    /**
     * 食材を直す。
     *
     * <p><b>単位は変えられます。</b>ただし変えても過去の数量は換算されません。
     * 「g で 1200 と記録したものを『パック』に変えたら 1200 パックになる」ので、
     * 画面で警告を出しています。ここで機械的に換算すると、
     * どの記録が換算済みか分からなくなって傷が深くなります。
     */
    @Transactional
    public void update(Long id, String name, IngredientUnit unit, BigDecimal lowThreshold,
                       BigDecimal costOverride, int sortOrder, boolean active, String memo) {
        ingredients.findById(id).ifPresent(ingredient -> {
            ingredient.setName(name);
            ingredient.setUnit(unit);
            ingredient.setLowThresholdQty(lowThreshold);
            ingredient.setCostOverride(normalizeCostOverride(costOverride));
            ingredient.setSortOrder(sortOrder);
            ingredient.setActive(active);
            ingredient.setMemo(memo);
            log.info("食材を更新しました: id={} {}", id, name);
        });
    }

    /**
     * 単価固定の 0 以下を「固定なし」に倒す。
     *
     * <p>画面側でも弾いていますが、ここでも倒しておきます。
     * 0 円の単価が正規の値として通ると、その食材を使う全メニューの原価から
     * 材料費が黙って消え、「単価不明」の警告も立たないまま
     * 原価率が実際より低く表示されるためです。
     */
    private BigDecimal normalizeCostOverride(BigDecimal costOverride) {
        if (costOverride != null && costOverride.signum() <= 0) {
            return null;
        }
        return costOverride;
    }

    /**
     * 使わなくなった印を付ける。<b>行は消しません。</b>
     *
     * <p>過去の仕入れ明細・棚卸し・レシピがこの行を指しているので、
     * 消すと去年の原価が計算できなくなります。
     */
    @Transactional
    public void deactivate(Long id) {
        ingredients.findById(id).ifPresent(ingredient -> {
            ingredient.setActive(false);
            log.info("食材を使用停止にしました: id={} {}", id, ingredient.getName());
        });
    }

    /**
     * この食材をレシピで使っているメニューの名前。
     *
     * <p>使用停止の前に知らせるためのものです。止めること自体は妨げません
     * （現物がもう無いなら止めるのが正しい）が、黙って止めると
     * その食材を使う全メニューの原価が「単価不明」になり、
     * 気づく手掛かりが原価表の警告だけになります。止める人に先に言うのが筋です。
     */
    @Transactional(readOnly = true)
    public List<String> menuItemsUsing(Long ingredientId) {
        List<String> names = new ArrayList<>();
        for (jp.komeko.order.inventory.domain.RecipeLine line : recipeLines.findByIngredient(ingredientId)) {
            String name = line.getMenuItem().getName();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ========================================================================
    //  入り数の記憶
    // ========================================================================

    /**
     * レシートの品名から、覚えている紐付けを引く。
     *
     * <p>照合は必ず正規化した形で行います。生の文字列で引くと
     * 「ｷｬﾍﾞﾂ」と「キャベツ」が別物になり、同じ食材を何度も教え直すことになります。
     */
    @Transactional(readOnly = true)
    public Optional<ItemAlias> recall(String rawItemText) {
        String normalized = AliasText.normalize(rawItemText);
        if (normalized == null) {
            return Optional.empty();
        }
        return aliases.findByAliasText(normalized);
    }

    /**
     * 複数の品名をまとめて引く。確認画面は明細が何行もあるので 1 往復で済ませる。
     *
     * @return 正規化した品名 → 記憶
     */
    @Transactional(readOnly = true)
    public Map<String, ItemAlias> recallAll(List<String> rawItemTexts) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawItemTexts) {
            String key = AliasText.normalize(raw);
            if (key != null && !normalized.contains(key)) {
                normalized.add(key);
            }
        }
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Map<String, ItemAlias> result = new HashMap<>();
        for (ItemAlias alias : aliases.findAllByAliasTextIn(normalized)) {
            result.put(alias.getAliasText(), alias);
        }
        return result;
    }

    /**
     * 「このレシートの品名は、この食材の◯◯ぶん」と覚える。
     *
     * <p>すでに覚えていれば上書きします。商品が入れ替わって内容量が変わることが
     * あるので、<b>最後に教わった内容を正</b>とします。
     *
     * @param rawItemText レシートに印字されていた品名（生のまま渡してよい）
     * @param ingredientId 紐付ける食材
     * @param qtyPerUnit  レシート 1 個あたりの量。分からないなら null（未学習として覚える）
     */
    @Transactional
    public ItemAlias learn(String rawItemText, Long ingredientId, BigDecimal qtyPerUnit) {
        String normalized = AliasText.normalize(rawItemText);
        if (normalized == null) {
            return null;
        }
        // 0 以下は「まだ教わっていない」として覚える。
        // 0 のまま学習済みにすると、在庫には積めないのに未学習の一覧からは消える、
        // という直す入口の無い状態になる（2026-08-31 のUI監査の指摘）。
        if (qtyPerUnit != null && qtyPerUnit.signum() <= 0) {
            qtyPerUnit = null;
        }
        Ingredient ingredient = ingredients.findById(ingredientId).orElseThrow();

        ItemAlias alias = aliases.findByAliasText(normalized).orElse(null);
        if (alias == null) {
            alias = new ItemAlias(normalized, rawItemText, ingredient, qtyPerUnit, LocalDateTime.now(clock));
        } else {
            alias.setIngredient(ingredient);
            alias.setQtyPerUnit(qtyPerUnit);
            alias.setSampleText(rawItemText);
            alias.setUpdatedAt(LocalDateTime.now(clock));
        }
        ItemAlias saved = aliases.save(alias);
        log.info("入り数を覚えました: 「{}」→ {} × {}", rawItemText, ingredient.getName(), qtyPerUnit);
        return saved;
    }

    /** 覚えている紐付けの一覧（新しい順）。 */
    @Transactional(readOnly = true)
    public List<ItemAlias> allAliases() {
        return aliases.findAllWithIngredient();
    }

    /**
     * まだ内容量を教わっていない紐付け。
     *
     * <p>画面に「あと ◯ 件」と出すためのものです。
     * 宿題の残りが見えていれば人は片付けますが、
     * どこかに埋もれていると永遠に放置されます。
     */
    @Transactional(readOnly = true)
    public List<ItemAlias> unlearnedAliases() {
        return aliases.findUnlearned();
    }

    /** 食材に紐付いているのに在庫に積めていない仕入れ明細。 */
    @Transactional(readOnly = true)
    public List<jp.komeko.order.inventory.domain.PurchaseLine> linesNeedingQuantity() {
        return purchaseLines.findNeedingQuantityLearning();
    }

    /**
     * すでにある記憶に、あとから内容量だけ教える。
     *
     * <p>確認画面で「食材はこれだが量が分からない」と保存した行の宿題を、
     * 落ち着いたときに片付けるための入口です。
     *
     * <p><b>すでに保存した仕入れ明細はさかのぼって直しません。</b>
     * 効くのは次のレシートからです。過去の記録は、そのとき人が確認した状態で
     * 置いておくのが筋で、あとから機械が書き換えると
     * 「いつの時点で何を確認したのか」が分からなくなります。
     *
     * @return 覚え直した記憶。見つからなければ null
     */
    @Transactional
    public ItemAlias relearnQuantity(Long aliasId, BigDecimal qtyPerUnit) {
        ItemAlias alias = aliases.findById(aliasId).orElse(null);
        if (alias == null) {
            return null;
        }
        alias.setQtyPerUnit(qtyPerUnit);
        alias.setUpdatedAt(LocalDateTime.now(clock));
        log.info("入り数を覚え直しました: 「{}」= {} {}",
                alias.getAliasText(), qtyPerUnit, alias.getIngredient().getUnit().getSymbol());
        return alias;
    }

    /** 記憶を消す（間違えて覚えさせたときの取り消し）。 */
    @Transactional
    public void forget(Long aliasId) {
        aliases.findById(aliasId).ifPresent(alias -> {
            log.info("入り数の記憶を消しました: 「{}」", alias.getAliasText());
            aliases.delete(alias);
        });
    }
}
