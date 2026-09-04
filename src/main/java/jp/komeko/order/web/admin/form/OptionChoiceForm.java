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
 * <p><b>{@link #extraPrice} にマイナスを許すのはやめました（2026-09-04）。</b><br>
 * もとは「ソース抜きで −50 円」のような値引きを表現するためでしたが、
 * 下限が −10,000 円だったため、<b>単価そのものがマイナスになる</b>入力が通りました。
 * {@code OrderLine#recalculate} は {@code basePrice + オプション代} をそのまま単価にし、
 * 下限で止めていません。単価が負の品を注文すると、
 * <b>その卓の小計から他の品の代金が差し引かれます</b>。
 * 「+1000」と入れるつもりで「-1000」と打つだけで起こせます。
 *
 * <p>商品の価格側は {@code MenuItemForm} が {@code @Min(0)} なので、
 * ここを 0 以上に閉じれば <b>単価は構造的に負になりません</b>。
 * 注文時に単価を検査する「最後の網」を足す必要もなくなります。
 *
 * <p>値引きオプションは実データに 1 件も無く、使われていませんでした。
 * もう一度必要になったら、「値引き額は商品価格を超えない」という
 * 商品単位の検査とセットで戻してください。
 * 下限をただ緩めると、上の事故がそのまま戻ります。
 */
public class OptionChoiceForm {

    @NotBlank(message = "選択肢名を入力してください")
    @Size(max = 40, message = "選択肢名は40文字以内で入力してください")
    private String name;

    @NotNull(message = "追加料金を入力してください")
    @Min(value = 0, message = "追加料金は0円以上で入力してください")
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
