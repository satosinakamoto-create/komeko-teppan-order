package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.domain.RecipeLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商品 1 品の理論原価と原価率。<b>前職のエクセルの「原価」「原価率」列にあたるもの</b>。
 *
 * <p><b>税込と税抜の両方を出します。</b>用途が違うからです。
 * <ul>
 *   <li><b>税込</b> … 実際に出ていったお金。紙のレシートと突き合わせるときはこちら</li>
 *   <li><b>税抜</b> … 経営の数字。税率が動いても連続するので、
 *       目標管理・他店比較・帳簿はこちら</li>
 * </ul>
 * 税込だけ見ていると、税制改定の日に「税込原価率は変わっていないのに
 * 実際は儲かるようになった」という変化が見えません。
 * 税抜だけだと紙のレシートと数字が合いません。保存は税込だけなので、
 * 両方出しても増えるコストはありません。
 *
 * <p><b>「理論」原価であることを忘れないこと。</b>
 * 実際は打ち粉が飛び、まかないに回り、目分量でぶれます。
 * 理論と実際の差は棚卸しのたびに実測され、記録として積み上がります。
 *
 * @param menuItem           対象の商品
 * @param lines              レシピの行と、その行ぶんの原価
 * @param costIncludingTax   理論原価（円・税込）。レシピ未登録なら null
 * @param costNet            理論原価（円・税抜）
 * @param priceIncludingTax  売価（円・税込）。既存の {@code menu_item.price}
 * @param priceNet           売価（円・税抜）
 * @param unknownCostCount   単価が分からず原価に入れられなかった食材の数
 */
public record RecipeCost(
        MenuItem menuItem,
        List<LineCost> lines,
        BigDecimal costIncludingTax,
        BigDecimal costNet,
        int priceIncludingTax,
        int priceNet,
        int unknownCostCount
) {

    /**
     * レシピ 1 行と、その行が原価にいくら効いているか。
     *
     * <p>画面で行ごとの内訳を出すために持ちます。合計だけ見せられても
     * 「どの材料が高いのか」が分からず、原価を下げる手がかりになりません。
     *
     * @param line             レシピの行
     * @param unitCost         食材 1 単位あたりの単価（円・税込）。分からなければ null
     * @param costIncludingTax この行ぶんの原価（円・税込）。分からなければ null
     */
    public record LineCost(RecipeLine line, BigDecimal unitCost, BigDecimal costIncludingTax) {

        /** 単価が分からず、原価に入れられていない行か。 */
        public boolean isUnknown() {
            return costIncludingTax == null;
        }
    }

    /** レシピが 1 行も登録されていないか。 */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 原価率（税込・%）。売価が 0 なら null。
     *
     * <p>0 で割った結果を 0% と表示すると<b>嘘になります</b>
     * （原価率 0% は「原価がかからない」という意味になってしまう）。
     * 分からないときは分からないと出すのが正しい。
     */
    public BigDecimal costRateIncludingTax() {
        return rate(costIncludingTax, priceIncludingTax);
    }

    /** 原価率（税抜・%）。経営の数字として使うのはこちら。 */
    public BigDecimal costRateNet() {
        return rate(costNet, priceNet);
    }

    private BigDecimal rate(BigDecimal cost, int price) {
        if (cost == null || price <= 0) {
            return null;
        }
        return cost.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(price), 1, RoundingMode.HALF_UP);
    }

    /**
     * 原価が計算しきれていないか。
     *
     * <p>まだ一度も仕入れていない食材が混ざっていると、その分の原価が抜けます。
     * 抜けた原価率は<b>実際より低く出る</b>ので、
     * 「思ったより儲かる」と誤解させないよう画面で断ります。
     */
    public boolean isIncomplete() {
        return unknownCostCount > 0;
    }
}
