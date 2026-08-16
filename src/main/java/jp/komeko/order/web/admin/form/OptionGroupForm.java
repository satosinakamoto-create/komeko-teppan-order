package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * オプショングループ（「サイズ」「トッピング」など）の追加フォーム。
 *
 * <p>エンティティ {@code OptionGroup} を直接受け取らない理由は
 * {@link CategoryForm} と同じで、画面に出していない項目
 * （所属する商品や選択肢の一覧など）まで送信値で書き換えられるのを防ぐためです。
 *
 * <p><b>minSelect / maxSelect の意味</b>
 * <pre>
 *   min=0, max=1 … 選ばなくてもよい。選ぶなら 1 つだけ（ラジオボタン）
 *   min=1, max=1 … 必ず 1 つ選ぶ（ラジオボタン・必須）
 *   min=0, max=3 … 0〜3 個まで自由に選べる（チェックボックス）
 * </pre>
 *
 * <p>「最大 &lt; 最小」のような組み合わせは 1 つの項目だけでは判定できません。
 * こうした<b>複数項目にまたがるチェック</b>は Bean Validation のアノテーションでは書きにくいので、
 * コントローラ側で {@code BindingResult#rejectValue} を使って追加しています。
 */
public class OptionGroupForm {

    @NotBlank(message = "オプション名を入力してください")
    @Size(max = 40, message = "オプション名は40文字以内で入力してください")
    private String name;

    @NotNull(message = "最小選択数を入力してください")
    @Min(value = 0, message = "最小選択数は0以上で入力してください")
    @Max(value = 20, message = "最小選択数は20以下で入力してください")
    private Integer minSelect = 0;

    @NotNull(message = "最大選択数を入力してください")
    @Min(value = 1, message = "最大選択数は1以上で入力してください")
    @Max(value = 20, message = "最大選択数は20以下で入力してください")
    private Integer maxSelect = 1;

    @NotNull(message = "並び順を入力してください")
    @Min(value = 0, message = "並び順は0以上で入力してください")
    @Max(value = 9999, message = "並び順は9999以下で入力してください")
    private Integer sortOrder = 0;

    public OptionGroupForm() {
    }

    // ── getter / setter ──────────────────────────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMinSelect() {
        return minSelect;
    }

    public void setMinSelect(Integer minSelect) {
        this.minSelect = minSelect;
    }

    public Integer getMaxSelect() {
        return maxSelect;
    }

    public void setMaxSelect(Integer maxSelect) {
        this.maxSelect = maxSelect;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
