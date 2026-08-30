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

    @NumberFormat(pattern = "#.###")
    private BigDecimal quantity;

    private Integer amount;

    private Integer taxRatePercent;

    private Integer taxAmount;

    private PurchaseCategory category = PurchaseCategory.FOOD;

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
}
