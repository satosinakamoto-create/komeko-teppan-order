package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * オプションの選択肢（「チーズ追加 +150円」など）の追加フォーム。
 *
 * <p>エンティティ {@code OptionChoice} を直接受け取らないのは
 * {@link CategoryForm} と同じ理由（mass assignment の防止）です。
 * とくに {@code extraPrice} は代金に直結するので、
 * 受け取る項目をこのクラスで限定しておく意味があります。
 *
 * <p>{@link #extraPrice} にマイナスを許しているのは、
 * 「ソース抜きで −50 円」のような値引きオプションを表現できるようにするためです。
 */
public class OptionChoiceForm {

    @NotBlank(message = "選択肢名を入力してください")
    @Size(max = 40, message = "選択肢名は40文字以内で入力してください")
    private String name;

    @NotNull(message = "追加料金を入力してください")
    @Min(value = -10000, message = "追加料金は-10,000円以上で入力してください")
    @Max(value = 100000, message = "追加料金は100,000円以下で入力してください")
    private Integer extraPrice = 0;

    @NotNull(message = "並び順を入力してください")
    @Min(value = 0, message = "並び順は0以上で入力してください")
    @Max(value = 9999, message = "並び順は9999以下で入力してください")
    private Integer sortOrder = 0;

    /** この選択肢だけ品切れにするか。 */
    private boolean soldOut = false;

    /** 画面を開いた時点で選択済みにしておくか。 */
    private boolean defaultSelected = false;

    public OptionChoiceForm() {
    }

    // ── getter / setter ──────────────────────────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getExtraPrice() {
        return extraPrice;
    }

    public void setExtraPrice(Integer extraPrice) {
        this.extraPrice = extraPrice;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public boolean isDefaultSelected() {
        return defaultSelected;
    }

    public void setDefaultSelected(boolean defaultSelected) {
        this.defaultSelected = defaultSelected;
    }
}
