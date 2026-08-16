package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * オプションのまとまり（例:「サイズ」「トッピング」「辛さ」）。
 *
 * <p>{@link #minSelect} と {@link #maxSelect} の組み合わせで
 * 画面の入力部品と入力チェックが決まります。
 *
 * <pre>
 *   min=1, max=1 … 必ず 1 つ選ぶ           → ラジオボタン（例: サイズ）
 *   min=0, max=1 … 選ばなくてもよい／1つまで → ラジオ + 「選ばない」
 *   min=0, max=3 … 0〜3 個まで自由に        → チェックボックス（例: トッピング）
 * </pre>
 */
@Entity
@Table(name = "option_group")
public class OptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_item_id")
    private MenuItem menuItem;

    @NotBlank(message = "オプション名を入力してください")
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String name;

    /** 最低選択数。1 以上なら必須。 */
    @Min(0)
    @Column(nullable = false)
    private int minSelect = 0;

    /** 最大選択数。1 ならラジオ、2 以上ならチェックボックスとして描画する。 */
    @Min(1)
    @Column(nullable = false)
    private int maxSelect = 1;

    @Column(nullable = false)
    private int sortOrder = 0;

    /**
     * 選択肢。
     * {@code @BatchSize} は「LAZY な関連を読むとき、まとめて N 件分の SQL 1 回で読む」設定。
     * 商品一覧のように親が複数ある場面で N+1 問題を大幅に減らせます。
     */
    @OneToMany(mappedBy = "optionGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @BatchSize(size = 100)
    private List<OptionChoice> choices = new ArrayList<>();

    protected OptionGroup() {
    }

    public OptionGroup(String name, int minSelect, int maxSelect, int sortOrder) {
        this.name = name;
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
        this.sortOrder = sortOrder;
    }

    /** 必ず選ばないといけないグループか。 */
    public boolean isRequired() {
        return minSelect >= 1;
    }

    /** 単一選択（ラジオ）か。false ならチェックボックス。 */
    public boolean isSingleChoice() {
        return maxSelect <= 1;
    }

    public void addChoice(OptionChoice choice) {
        choices.add(choice);
        choice.setOptionGroup(this);
    }

    public Long getId() {
        return id;
    }

    public MenuItem getMenuItem() {
        return menuItem;
    }

    public void setMenuItem(MenuItem menuItem) {
        this.menuItem = menuItem;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMinSelect() {
        return minSelect;
    }

    public void setMinSelect(int minSelect) {
        this.minSelect = minSelect;
    }

    public int getMaxSelect() {
        return maxSelect;
    }

    public void setMaxSelect(int maxSelect) {
        this.maxSelect = maxSelect;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public List<OptionChoice> getChoices() {
        return choices;
    }
}
