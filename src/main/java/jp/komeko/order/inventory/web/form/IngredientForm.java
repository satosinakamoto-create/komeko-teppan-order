package jp.komeko.order.inventory.web.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientCategory;
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

    /**
     * 探すときの分類。<b>必須にしません。</b>
     *
     * <p>仕込み中に食材を足すことがあり、そこで分類を考えさせて手が止まるより、
     * 名前と単位だけで登録できるほうが現場に合います。
     * 決めていないものは一覧で「未分類」に集まるので、あとからまとめて直せます。
     */
    private IngredientCategory category;

    /** 残量がこれを下回ったら警告。空欄なら量では警告しない。 */
    @DecimalMin(value = "0", message = "警告残量は0以上で入力してください")
    // 列は precision=12, scale=3（Ingredient.lowThresholdQty）
    @jakarta.validation.constraints.Digits(integer = 9, fraction = 3,
            message = "警告残量が大きすぎます。整数は 9 桁まで・小数は 3 桁までで入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal lowThresholdQty;

    /**
     * 単価の手動固定（円・税込・1単位あたり）。空欄なら最新の仕入れから自動。
     *
     * <p><b>0 は弾きます。</b>「固定をやめたい」つもりで 0 を入れる誤操作があり得ますが、
     * 0 を通すとその食材の原価が黙って 0 円になり、原価率が実際より低く出ます
     * （「単価不明」の警告も立ちません）。やめるときは空欄、が正しい操作です。
     */
    @DecimalMin(value = "0", inclusive = false,
            message = "単価を固定する場合は0より大きい値を。固定をやめるときは空欄にしてください")
    // 列は precision=12, scale=4（Ingredient.costOverride）。
    // 小数を 1 桁多く取っているぶん、整数は 8 桁までしか入らない
    @jakarta.validation.constraints.Digits(integer = 8, fraction = 4,
            message = "単価が大きすぎます。整数は 8 桁まで・小数は 4 桁までで入力してください")
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
        form.category = ingredient.getCategory();
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

    public IngredientCategory getCategory() {
        return category;
    }

    public void setCategory(IngredientCategory category) {
        this.category = category;
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
