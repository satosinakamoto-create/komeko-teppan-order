package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.inventory.domain.RecipeLine;
import jp.komeko.order.inventory.repository.ConsumptionLookupRepository;
import jp.komeko.order.inventory.repository.RecipeLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「売れた商品」を「減った食材」に翻訳する係。
 *
 * <pre>
 *   消費(食材i) = Σ 売れた数(商品m) × レシピの使用量(商品m, 食材i)
 * </pre>
 *
 * <p><b>既存の注文データを読むだけで、1 行も書きません。</b>
 * 注文の側は在庫モジュールの存在を知りません。
 * QR 注文はいままでどおり動き、この計算は横から結果を見ているだけです。
 *
 * <p><b>キャンセルした注文は数えません。</b>会計から外す判定と同じものを使います。
 * ただし売上の集計とは条件が違い、<b>受渡済みに限りません</b>。
 * 調理を始めていれば材料はもう減っているからです。
 * お金と材料は減るタイミングが違う、というだけのことです。
 *
 * <p><b>レシピ未登録のメニューは、この計算から静かに漏れます。</b>
 * それ自体は設計どおり（全部そろわなくても動く）ですが、
 * 漏れた分は「使ったのに減っていない」ことになり、予測が甘く出ます。
 * だから {@link #menuItemsWithoutRecipe} を画面に常時出します。
 */
@Service
public class ConsumptionService {

    private final RecipeLineRepository recipes;
    private final ConsumptionLookupRepository orders;

    public ConsumptionService(RecipeLineRepository recipes, ConsumptionLookupRepository orders) {
        this.recipes = recipes;
        this.orders = orders;
    }

    /**
     * 期間に減った食材の量を、食材ごとに出す。
     *
     * @param fromExclusive この日は含めない（棚卸しの日はすでに実測に含まれているため）。
     *                      null なら下限なし
     * @param to            この日まで（含む）
     * @return 食材 id → 減った量。減っていない食材は含まれない
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> consumedBetween(LocalDate fromExclusive, LocalDate to) {
        return consumedBetween(fromExclusive, to, recipes.findAllWithRelations());
    }

    /**
     * レシピをすでに読んである場合の版。
     *
     * <p>在庫一覧は棚卸し日ごとに何度かこの計算を回すので、
     * そのたびにレシピを読み直さずに済むよう、外から渡せるようにしています。
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> consumedBetween(LocalDate fromExclusive, LocalDate to,
                                                 List<RecipeLine> recipeLines) {
        if (recipeLines.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> soldByMenuItem = new HashMap<>();
        for (ConsumptionLookupRepository.SoldQuantity row
                : orders.sumSoldByMenuItem(fromExclusive, to, OrderStatus.CANCELED)) {
            soldByMenuItem.put(row.getMenuItemId(), row.getQuantity());
        }
        if (soldByMenuItem.isEmpty()) {
            return Map.of();
        }

        Map<Long, BigDecimal> consumed = new HashMap<>();
        for (RecipeLine line : recipeLines) {
            Long sold = soldByMenuItem.get(line.getMenuItem().getId());
            if (sold == null || sold == 0) {
                continue;
            }
            BigDecimal used = line.getQtyPerItem().multiply(BigDecimal.valueOf(sold));
            consumed.merge(line.getIngredient().getId(), used, BigDecimal::add);
        }
        return consumed;
    }

    /**
     * 直近の期間から、1 営業日あたりの平均消費を出す。
     *
     * <p><b>営業日は実績から数えます。</b>「注文が 1 件以上あった日」を営業日とするので、
     * 水木定休も臨時休業も貸切も、設定を何も書かずに除かれます。
     * カレンダーを別に管理すると、必ずどこかで実態とずれます。
     *
     * @return 食材 id → 1 営業日あたりの平均消費。営業日が 0 なら空
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> dailyAverageOver(LocalDate from, LocalDate to,
                                                  List<RecipeLine> recipeLines) {
        long businessDays = orders.countBusinessDays(from, to, OrderStatus.CANCELED);
        if (businessDays <= 0) {
            return Map.of();
        }

        // from を含めたいので、下限は「その前日より後」として渡す
        Map<Long, BigDecimal> total = consumedBetween(from.minusDays(1), to, recipeLines);

        Map<Long, BigDecimal> average = new HashMap<>();
        BigDecimal days = BigDecimal.valueOf(businessDays);
        for (Map.Entry<Long, BigDecimal> entry : total.entrySet()) {
            average.put(entry.getKey(),
                    entry.getValue().divide(days, 4, java.math.RoundingMode.HALF_UP));
        }
        return average;
    }

    /** レシピが 1 行でも登録されている商品の id。 */
    @Transactional(readOnly = true)
    public List<Long> menuItemsWithRecipe() {
        return recipes.findMenuItemIdsWithRecipe();
    }

    /**
     * レシピが未登録の商品。
     *
     * <p>この一覧を画面に出し続けるのが<b>予測の甘さに対する唯一の保険</b>です。
     * 登録漏れは静かに効き、しかも汎用食材ほど大きく効きます。
     * 「まだある」と言われて発注しなかった、という形で実害が出るので、
     * 見えなくしてはいけません。
     */
    @Transactional(readOnly = true)
    public List<Long> menuItemsWithoutRecipe(List<Long> allMenuItemIds) {
        List<Long> withRecipe = menuItemsWithRecipe();
        return allMenuItemIds.stream().filter(id -> !withRecipe.contains(id)).toList();
    }
}
