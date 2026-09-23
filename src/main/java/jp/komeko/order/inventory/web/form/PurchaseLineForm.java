package jp.komeko.order.inventory.web.form;

import jp.komeko.order.inventory.domain.PurchaseCategory;
import org.springframework.format.annotation.NumberFormat;

import java.math.BigDecimal;

/**
 * 確認画面の明細 1 行ぶんの入力欄。
 *
 * <p>数値をすべてラッパー型（{@code Integer}）にしているのは、
 * <b>空欄と 0 を区別する</b>ためです。{@code int} だと未入力が 0 になり、
 * 「0 円の行」と「まだ書いていない行」が見分けられなくなります。
 */
public class PurchaseLineForm {

    private String itemText;

    // 列は precision=12, scale=3（PurchaseLine.quantity）。
    // 上限が無いと 13 桁で DB の桁あふれが 500 になる（全体点検 #4）
    @jakarta.validation.constraints.Digits(integer = 9, fraction = 3,
            message = "個数が大きすぎます。整数は 9 桁まで・小数は 3 桁までで入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal quantity;

    private Integer amount;

    private Integer taxRatePercent;

    private Integer taxAmount;

    private PurchaseCategory category = PurchaseCategory.FOOD;

    /**
     * どの食材か。空欄なら在庫には入らない（経費としてだけ記録される）。
     *
     * <p>覚えている品名なら、確認画面を開いた時点ですでに選ばれています。
     */
    private Long ingredientId;

    /**
     * 在庫に積む量（食材の単位で）。空欄なら金額だけ記録し、在庫には積まない。
     *
     * <p>覚えていれば「1 パック = 100g」に個数を掛けた値が入ります。
     * <b>空欄でも保存できます。</b>お金の記録としては完全なので、
     * ここを必須にすると忙しい日にレシート入力の手が止まります。
     */
    // 列は precision=12, scale=3（PurchaseLine.stockQty）
    @jakarta.validation.constraints.Digits(integer = 9, fraction = 3,
            message = "在庫に入れる量が大きすぎます。整数は 9 桁まで・小数は 3 桁までで入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal stockQty;

    /**
     * この行の紐付けを覚えるか。
     *
     * <p>初期値は true です。人が食材を選び直したなら、それが正しい紐付けだからで、
     * わざわざ「覚えますか」と聞き直す意味がありません。
     * 覚えたくない例外（その日限りの特売品など）のときだけ外してもらいます。
     */
    private boolean learnAlias = true;

    public PurchaseLineForm() {
        // フォームバインド用
    }

    public PurchaseLineForm(String itemText, BigDecimal quantity, Integer amount,
                            Integer taxRatePercent, PurchaseCategory category) {
        this.itemText = itemText;
        this.quantity = quantity;
        this.amount = amount;
        this.taxRatePercent = taxRatePercent;
        this.category = category;
    }

    /**
     * 使われていない行か。
     *
     * <p>確認画面には予備の空行を並べておき、書かれた行だけを保存します。
     * 行を増やすのに JavaScript を使わずに済ませるための割り切りです。
     */
    public boolean isBlank() {
        return (itemText == null || itemText.isBlank()) && amount == null;
    }

    public String getItemText() {
        return itemText;
    }

    public void setItemText(String itemText) {
        this.itemText = itemText;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public Integer getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(Integer taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public Integer getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(Integer taxAmount) {
        this.taxAmount = taxAmount;
    }

    public PurchaseCategory getCategory() {
        return category;
    }

    public void setCategory(PurchaseCategory category) {
        this.category = category;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public void setIngredientId(Long ingredientId) {
        this.ingredientId = ingredientId;
    }

    public BigDecimal getStockQty() {
        return stockQty;
    }

    public void setStockQty(BigDecimal stockQty) {
        this.stockQty = stockQty;
    }

    public boolean isLearnAlias() {
        return learnAlias;
    }

    public void setLearnAlias(boolean learnAlias) {
        this.learnAlias = learnAlias;
    }

    /**
     * 食材は選ばれているのに量が空欄か。画面で黄色く出す判定。
     *
     * <p>食材を選んでいない行（洗剤など）は黄色にしません。
     * <b>直すべきものだけを光らせる</b>のが、警告が無視されないための条件です。
     */
    public boolean isMissingStockQty() {
        return ingredientId != null && (stockQty == null || stockQty.signum() == 0);
    }

    /**
     * 食材の行なのに、まだどの食材にも紐付いていないか（2026-09-14）。
     *
     * <p>この行だけ画面で薄い赤にして、「食材登録」のボタンを出します。
     * 費目が食材でない行（洗剤・ゴミ袋）は対象外。あちらは紐付けないのが正しい姿で、
     * 光らせると「直すべきもの」の意味が薄れます（{@link #isMissingStockQty()} と同じ考え）。
     */
    public boolean isUnlinkedFood() {
        return category == PurchaseCategory.FOOD && ingredientId == null && !isBlank();
    }

    /**
     * 品名から、食材の名前の候補を作る。
     *
     * <p>レシートの品名には包装の都合が混ざります。
     * <pre>
     *   エリンギ 2P   → エリンギ
     *   米粉 10kg     → 米粉
     *   大葉 3枚      → 大葉
     * </pre>
     * ここを素通しにすると、食材マスタに「エリンギ 2P」と「エリンギ 3P」が
     * 別々に並びます。<b>同じ食材が分裂すると在庫も原価も割れる</b>ので、
     * 数量・単位・包装の表記は落としてから渡します。
     *
     * <p>あくまで候補です。おかしければ画面で直せます。
     */
    public String suggestedIngredientName() {
        if (itemText == null) {
            return "";
        }
        String s = itemText.trim();
        // 「2P」「10kg」「3枚」のような、数字で始まる塊を末尾から削る。
        // 全角スペースも区切りに数える（レシートの読み取りでよく混ざる）
        s = s.replaceAll("[\\s\\u3000]*\\d+(\\.\\d+)?\\s*(P|p|パック|袋|入|本|枚|個|玉|束|g|ｇ|kg|ｋｇ|ml|L|cc)?$", "");
        return s.trim();
    }

    /**
     * その場で食材を作るときに選ぶ単位。<b>保存はしません</b>（画面の一時的な入力）。
     *
     * <p>品名はレシートから入るので、人が決めるのはここだけ。
     * 「キャベツ 3」の 3 は 3 玉ですが、レシピはグラムで使う——
     * この換算は人しか決められないので、AI に推測させません。
     */
    private jp.komeko.order.inventory.domain.IngredientUnit newUnit
            = jp.komeko.order.inventory.domain.IngredientUnit.GRAM;

    public jp.komeko.order.inventory.domain.IngredientUnit getNewUnit() {
        return newUnit;
    }

    public void setNewUnit(jp.komeko.order.inventory.domain.IngredientUnit newUnit) {
        this.newUnit = newUnit;
    }
}
