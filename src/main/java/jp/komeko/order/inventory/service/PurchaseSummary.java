package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.PurchaseCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * ある期間の仕入れ・経費のまとめ。
 *
 * <p><b>原価率を税込と税抜の両方で出す理由</b><br>
 * 用途が違うからです。
 * <ul>
 *   <li><b>税込</b> … 実際に財布から出た額。レシートと突き合わせるときはこちら</li>
 *   <li><b>税抜</b> … 経営の数字。税率が変わっても連続するので、目標管理や他店との比較に使う</li>
 * </ul>
 * 食材が 8%、売上が 10% と税率が違うので、税込どうしで割ると
 * 原価率が実態より 0.5 ポイントほど低く出ます。
 * また税率が改定されると、税込の原価率だけが階段状にずれて、
 * 仕入値が動いたのか制度が変わったのか区別できなくなります。
 *
 * @param from                 集計期間の開始日
 * @param to                   集計期間の終了日
 * @param purchaseCount        仕入れの件数
 * @param categories           費目ごとの内訳
 * @param totalIncludingTax    仕入れ・経費の総額（税込）
 * @param totalNet             同（税抜）
 * @param foodCostIncludingTax 食材だけの合計（税込）＝原価率の分子
 * @param foodCostNet          同（税抜）
 * @param salesIncludingTax    期間の売上（税込）
 * @param salesNet             期間の売上（税抜）
 */
public record PurchaseSummary(
        LocalDate from,
        LocalDate to,
        int purchaseCount,
        List<CategoryRow> categories,
        int totalIncludingTax,
        int totalNet,
        int foodCostIncludingTax,
        int foodCostNet,
        long salesIncludingTax,
        long salesNet
) {

    /**
     * 費目ごとの内訳 1 行。
     *
     * @param category         費目
     * @param amountIncludeTax 税込合計
     * @param amountNet        税抜合計
     */
    public record CategoryRow(PurchaseCategory category, int amountIncludeTax, int amountNet) {
    }

    /**
     * 実際原価率（税込ベース、%）。売上が 0 なら null。
     *
     * <p>null を返すのは、0 で割れないからです。ここで 0% を返すと
     * 「原価率 0%」という嘘の数字が画面に出てしまいます。
     */
    public BigDecimal costRatioIncludingTax() {
        return ratio(foodCostIncludingTax, salesIncludingTax);
    }

    /** 実際原価率（税抜ベース、%）。売上が 0 なら null。 */
    public BigDecimal costRatioNet() {
        return ratio(foodCostNet, salesNet);
    }

    private BigDecimal ratio(long cost, long sales) {
        if (sales <= 0) {
            return null;
        }
        return BigDecimal.valueOf(cost)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sales), 1, RoundingMode.HALF_UP);
    }

    /** 期間に 1 件も記録がないか。 */
    public boolean isEmpty() {
        return purchaseCount == 0;
    }
}
