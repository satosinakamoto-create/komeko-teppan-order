package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * オプションの選択肢 1 つ（例:「チーズ追加 +150円」「大盛り +200円」）。
 */
@Entity
@Table(name = "option_choice")
public class OptionChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_group_id")
    private OptionGroup optionGroup;

    @NotBlank(message = "選択肢名を入力してください")
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String name;

    /** 追加料金（税込・円）。0 なら無料オプション。マイナスも可（例: ソース抜きで -50円）。 */
    @Column(nullable = false)
    private int extraPrice = 0;

    /** この選択肢だけ品切れ（例: チーズが切れた）。 */
    @Column(nullable = false)
    private boolean soldOut = false;

    /** 画面を開いた時点で選択済みにしておくか。 */
    @Column(nullable = false)
    private boolean defaultSelected = false;

    @Column(nullable = false)
    private int sortOrder = 0;

    protected OptionChoice() {
    }

    public OptionChoice(String name, int extraPrice, int sortOrder) {
        this.name = name;
        this.extraPrice = extraPrice;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public OptionGroup getOptionGroup() {
        return optionGroup;
    }

    public void setOptionGroup(OptionGroup optionGroup) {
        this.optionGroup = optionGroup;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getExtraPrice() {
        return extraPrice;
    }

    public void setExtraPrice(int extraPrice) {
        this.extraPrice = extraPrice;
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

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
