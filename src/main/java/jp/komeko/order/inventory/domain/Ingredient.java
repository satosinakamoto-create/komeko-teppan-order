package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * 食材。「キャベツ」「米粉」といった、在庫を数える対象そのもの。
 *
 * <p><b>既存の {@code menu_item.stock_remaining} とは別物です。</b>
 * あちらは「その商品をあと何食売れるか」という<b>販売の残数</b>で、
 * 店主が手で入れるもの。こちらは<b>材料そのものの在庫</b>で、
 * 仕入れと注文から自動で算出されます。混ぜてはいけません。
 *
 * <p><b>在庫の数量はここに持ちません。</b>
 * 現在庫は {@code stocktake}（棚卸し）・{@code purchase_line}（入庫）・
 * 注文×レシピ（消費）から毎回計算します。
 * 数量をここに持つと「計算した値」と「保存した値」の 2 つが生まれ、
 * どちらが正しいのか誰にも分からなくなるからです。銀行口座と同じ考え方で、
 * 残高は保存せず入出金の記録から出します。
 *
 * <p><b>消さずに使わなくする</b><br>
 * 使わなくなった食材も {@link #active} を false にするだけで、行は残します。
 * 過去の仕入れ明細・棚卸し・レシピがこの行を指しているので、
 * 消すと去年の原価が計算できなくなります。
 */
@Entity
@Table(name = "ingredient", indexes = {
        @Index(name = "idx_ingredient_name", columnList = "name", unique = true)
})
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 食材名。店の中で通じる呼び方でよい（「キャベツ」「米粉」）。 */
    @Column(nullable = false, unique = true, length = 60)
    private String name;

    /** 在庫を数える単位。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private IngredientUnit unit = IngredientUnit.GRAM;

    /**
     * 探すときの分類（野菜・肉…）。<b>null は「まだ決めていない」</b>。
     *
     * <p>NOT NULL にして OTHER を既定にすると、分類し忘れた食材と
     * 本当にその他な食材が同じ棚に混ざり、片付けようがなくなります。
     * 空を許して「未分類」として集めるほうが、あとから直せます。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private IngredientCategory category;

    /**
     * 残量がこれを下回ったら警告する量。null なら日数だけで警告する。
     *
     * <p>「あと何日もつか」は消費のペースが分かって初めて出せる数字です。
     * レシピを登録していない食材ではペースが出ないので、
     * 「残り 500g を切ったら教えて」と量で言えるようにしてあります。
     */
    @Column(name = "low_threshold_qty", precision = 12, scale = 3)
    private BigDecimal lowThresholdQty;

    /**
     * 単価の手動上書き（円・税込・1 単位あたり）。null なら最新の仕入れから自動。
     *
     * <p>ふだんは最新の仕入価格を自動で使います。値上がりが翌日の原価率に
     * そのまま出るのが利点です。ただし「特売でたまたま安く買えた」ような日の
     * 値段を基準にしたくないときのために、手で固定できるようにしてあります。
     */
    @Column(name = "cost_override", precision = 12, scale = 4)
    private BigDecimal costOverride;

    /** 使っている食材か。false でも行は消さない。 */
    @Column(nullable = false)
    private boolean active = true;

    /** 一覧での並び順。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** メモ（保管場所、発注先など）。 */
    @Column(length = 200)
    private String memo;

    protected Ingredient() {
        // JPA 用
    }

    public Ingredient(String name, IngredientUnit unit) {
        this.name = name;
        this.unit = unit;
    }

    /** 単位付きの表示（「1200 g」）。数量の表示を 1 箇所にまとめるため。 */
    public String format(BigDecimal quantity) {
        return format(quantity, true);
    }

    /**
     * 数量を人が読む形にする。
     *
     * <p>DB からは {@code 4200.000} のように小数位が付いて返ります。
     * そのまま画面に出すと、内訳のような数字が並ぶところで
     * <b>意味のない 0 が視線を奪います</b>。落として出します。
     *
     * @param withUnit 単位を付けるか。同じ単位が 1 行に何度も出るところでは false
     */
    public String format(BigDecimal quantity, boolean withUnit) {
        if (quantity == null) {
            return "―";
        }
        String number = quantity.stripTrailingZeros().toPlainString();
        return withUnit ? number + " " + unit.getSymbol() : number;
    }

    // ── getter / setter（Lombok は使わない規約） ──

    public Long getId() {
        return id;
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

    /** 画面に出す分類名。決めていなければ「未分類」。 */
    public String getCategoryLabel() {
        return category == null ? "未分類" : category.getLabel();
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
