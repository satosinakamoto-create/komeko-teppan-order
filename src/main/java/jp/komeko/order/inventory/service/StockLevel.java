package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.Ingredient;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ある食材の、ある時点の在庫。<b>計算した結果であって、保存されている値ではありません。</b>
 *
 * <p>内訳（起点・入庫・消費・補正）まで持たせているのは、
 * 画面に「なぜこの数字になったか」を出せるようにするためです。
 * 在庫が合わないときにいちばん知りたいのは合計ではなく<b>どこがずれたか</b>で、
 * 結果の数字だけ見せられても人は納得しません。
 *
 * @param ingredient           対象の食材
 * @param quantity             現在庫。マイナスもあり得る（隠さない）
 * @param baselineOn           起点になった棚卸しの日。null なら棚卸しがまだ 1 度もない
 * @param baselineQty          起点の実測量
 * @param received             起点より後の入庫合計
 * @param consumed             起点より後の消費合計（注文 × レシピ）
 * @param adjusted             起点より後の増減合計（廃棄・まかない。ふつうは負）
 * @param unitCostIncludingTax 1 単位あたりの単価（円・税込）。仕入れ実績がなければ null
 * @param unitCostNet          同じく税抜
 * @param costIsOverridden     単価が手動で固定されているか
 * @param daysLeft             あと何営業日もつか。消費の実績がなければ null（Step 3 で埋まる）
 * @param dailyConsumption     1 営業日あたりの平均消費。予測の根拠として表示する
 */
public record StockLevel(
        Ingredient ingredient,
        BigDecimal quantity,
        LocalDate baselineOn,
        BigDecimal baselineQty,
        BigDecimal received,
        BigDecimal consumed,
        BigDecimal adjusted,
        BigDecimal unitCostIncludingTax,
        BigDecimal unitCostNet,
        boolean costIsOverridden,
        Integer daysLeft,
        BigDecimal dailyConsumption
) {

    /**
     * これを超える予測は、具体的な日数を出さない。
     *
     * <p>30 営業日といえば 1 か月半ほど先です。それより遠い予測は、
     * 途中でメニューが変わり、季節が変わり、仕入れが何度も入るので、
     * <b>当たらないうえに判断にも使えません</b>。
     *
     * <p>実際、サンプルデータで「あと 1173 営業日」と表示されました。
     * 計算としては正しいのですが、こういう数字が並ぶと
     * <b>横に出ている「あと 3 営業日」まで信用されなくなります</b>。
     * 出す数字を絞るほうが、画面全体の信頼が上がります。
     */
    private static final int FORECAST_HORIZON_DAYS = 30;

    /**
     * マイナス在庫か。
     *
     * <p><b>マイナスは隠しません。</b>0 で止めて表示すると
     * 「システムは正しい数字を出している」と見えてしまいますが、実際は
     * 棚卸しが必要な状態です。データの嘘をつかず、画面で棚卸しを促します。
     */
    public boolean isNegative() {
        return quantity.signum() < 0;
    }

    /** 当分もつ（具体的な日数を出す意味がない）か。 */
    public boolean isPlentiful() {
        return daysLeft != null && daysLeft > FORECAST_HORIZON_DAYS;
    }

    /** 「当分もつ」と言うときの目安日数。画面の文言に使う。 */
    public int forecastHorizon() {
        return FORECAST_HORIZON_DAYS;
    }

    /** 食材に設定した警告残量を下回っているか。 */
    public boolean isBelowThreshold() {
        BigDecimal threshold = ingredient.getLowThresholdQty();
        return threshold != null && quantity.compareTo(threshold) < 0;
    }

    /** 一度も棚卸ししていないか。この場合、起点は 0 として計算している。 */
    public boolean hasNoBaseline() {
        return baselineOn == null;
    }

    /**
     * 何らかの注意が要る状態か。一覧のバッジ表示に使う。
     */
    public boolean needsAttention() {
        return isNegative() || isBelowThreshold();
    }
}
