package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;
import jp.komeko.order.domain.MenuItem;

import java.math.BigDecimal;

/**
 * 「この商品 1 品に、この食材をどれだけ使うか」。前職のエクセルの 1 行にあたるもの。
 *
 * <p><b>これが入って初めて、注文が在庫を減らすようになります。</b>
 * それまで在庫は仕入れと棚卸しだけで動いていました。
 * レシピを登録したメニューから順に、売れた分が自動で引かれます。
 *
 * <p><b>全部そろわなくても動きます。</b>登録した商品の分だけ精度が上がる作りです。
 * 20 品中 5 品しか登録していなくても、その 5 品ぶんは正しく減ります。
 * 完璧を待つと永遠に始まらないので、始められる形にしてあります。
 *
 * <p><b>ただし、登録漏れは黙って効きます。</b>
 * 米粉のように多くのメニューにまたがる食材ほど影響が大きく、
 * 20 品中 15 品しか登録していなければ消費の 25% が計上されず、
 * 予測が甘く出ます（「まだある」と言われて発注しない事故）。
 * だから画面に「レシピ未登録 ◯件」を常に出しています。
 *
 * <p><b>ここは履歴ではなく「いまの設定」です。</b>
 * だから {@code menu_item} に本物の外部キーを張って、整合性を DB に守らせます。
 * 過去の注文明細（{@code order_line}）が品名や価格をスナップショットで持つのとは
 * 役割が違います。あちらは「あの日いくらで売った」という事実の記録です。
 */
@Entity
@Table(name = "recipe_line", indexes = {
        @Index(name = "idx_recipe_line_menu_item", columnList = "menu_item_id"),
        @Index(name = "idx_recipe_line_ingredient", columnList = "ingredient_id")
})
public class RecipeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    /** 1 品あたりの使用量（食材の単位で）。 */
    @Column(name = "qty_per_item", nullable = false, precision = 12, scale = 3)
    private BigDecimal qtyPerItem;

    @Column(length = 100)
    private String memo;

    protected RecipeLine() {
        // JPA 用
    }

    public RecipeLine(MenuItem menuItem, Ingredient ingredient, BigDecimal qtyPerItem) {
        this.menuItem = menuItem;
        this.ingredient = ingredient;
        this.qtyPerItem = qtyPerItem;
    }

    /**
     * この 1 行ぶんの原価（円）。単価が分からなければ null。
     *
     * @param unitCost 食材 1 単位あたりの単価（税込または税抜）
     */
    public BigDecimal costOf(BigDecimal unitCost) {
        if (unitCost == null) {
            return null;
        }
        return qtyPerItem.multiply(unitCost);
    }

    // ── getter / setter ──

    public Long getId() {
        return id;
    }

    public MenuItem getMenuItem() {
        return menuItem;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public void setIngredient(Ingredient ingredient) {
        this.ingredient = ingredient;
    }

    public BigDecimal getQtyPerItem() {
        return qtyPerItem;
    }

    public void setQtyPerItem(BigDecimal qtyPerItem) {
        this.qtyPerItem = qtyPerItem;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
