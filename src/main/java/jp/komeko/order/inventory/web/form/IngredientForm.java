package jp.komeko.order.inventory.web.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import org.springframework.format.annotation.NumberFormat;

import java.math.BigDecimal;

/**
 * 食材の登録・編集フォーム。
 *
 * <p>エンティティを直接バインドしない、というのが既存の規約です。
 * 画面から送られてくる値をそのままエンティティに入れると、
 * 画面に出していない項目まで書き換えられる余地が残ります。
 */
public class IngredientForm {

    @NotBlank(message = "食材名を入力してください")
    @Size(max = 60, message = "食材名は60文字以内で入力してください")
    private String name;

    @NotNull(message = "単位を選んでください")
    private IngredientUnit unit = IngredientUnit.GRAM;

    /** 残量がこれを下回ったら警告。空欄なら量では警告しない。 */
    @DecimalMin(value = "0", message = "警告残量は0以上で入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal lowThresholdQty;

    /** 単価の手動固定（円・税込・1単位あたり）。空欄なら最新の仕入れから自動。 */
    @DecimalMin(value = "0", message = "単価は0以上で入力してください")
    @NumberFormat(pattern = "#.####")
    private BigDecimal costOverride;

    private int sortOrder = 0;

    private boolean active = true;

    @Size(max = 200, message = "メモは200文字以内で入力してください")
    private String memo;

    public IngredientForm() {
        // フォームバインド用
    }

    /** 既存の食材から編集フォームを作る。 */
    public static IngredientForm of(Ingredient ingredient) {
        IngredientForm form = new IngredientForm();
        form.name = ingredient.getName();
        form.unit = ingredient.getUnit();
        form.lowThresholdQty = ingredient.getLowThresholdQty();
        form.costOverride = ingredient.getCostOverride();
        form.sortOrder = ingredient.getSortOrder();
        form.active = ingredient.isActive();
        form.memo = ingredient.getMemo();
        return form;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public IngredientUnit getUnit() {
        return unit;
    }

    public void setUnit(IngredientUnit unit) {
        this.unit = unit;
    }

    public BigDecimal getLowThresholdQty() {
        return lowThresholdQty;
    }

    public void setLowThresholdQty(BigDecimal lowThresholdQty) {
        this.lowThresholdQty = lowThresholdQty;
    }

    public BigDecimal getCostOverride() {
        return costOverride;
    }

    public void setCostOverride(BigDecimal costOverride) {
        this.costOverride = costOverride;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
