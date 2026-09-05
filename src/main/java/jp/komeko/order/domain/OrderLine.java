package jp.komeko.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 注文明細（伝票の 1 行）。
 *
 * <p>商品マスタ（{@link MenuItem}）への外部キーではなく、
 * 商品 ID と商品名・価格の「コピー」を持っています。
 * こうしておくと、あとから商品を削除・値上げしても過去の伝票は当時のまま残ります。
 */
@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 元になった商品の ID（集計用。商品が消えていても参照は残す）。 */
    @Column(name = "menu_item_id")
    private Long menuItemId;

    /** 注文時点の商品名（スナップショット）。 */
    @Column(nullable = false, length = 60)
    private String menuItemName;

    /** 商品本体の税込単価（オプション代を含まない）。 */
    @Column(nullable = false)
    private int basePrice;

    /** オプション込みの税込単価。 */
    @Column(nullable = false)
    private int unitPrice;

    @Column(nullable = false)
    private int quantity;

    /** unitPrice × quantity。あえて保存しておくと集計 SQL が単純になる。 */
    @Column(nullable = false)
    private int lineTotal;

    /** この品 1 個あたりの標準調理時間（分）。 */
    @Column(nullable = false)
    private int cookMinutes;

    @OneToMany(mappedBy = "orderLine", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 200)
    private List<OrderLineOption> options = new ArrayList<>();

    protected OrderLine() {
    }

    public OrderLine(Long menuItemId, String menuItemName, int basePrice, int quantity, int cookMinutes) {
        this.menuItemId = menuItemId;
        this.menuItemName = menuItemName;
        this.basePrice = basePrice;
        this.unitPrice = basePrice;
        this.quantity = quantity;
        this.cookMinutes = cookMinutes;
        this.lineTotal = basePrice * quantity;
    }

    public void addOption(OrderLineOption option) {
        options.add(option);
        option.setOrderLine(this);
        recalculate();
    }

    /** オプション代を反映して単価と小計を計算し直す。 */
    public void recalculate() {
        // getExtraPrice ではなく getSubtotal（1 個あたり × 個数）を足すこと。
        // 同じ選択肢を複数選べるグループでは個数が 2 以上になり得るので、
        // 単価をそのまま足すと足りなくなる。個数 1 のときは同じ値。
        int extra = options.stream().mapToInt(OrderLineOption::getSubtotal).sum();
        this.unitPrice = basePrice + extra;
        this.lineTotal = this.unitPrice * quantity;
    }

    /** 「チーズ追加 / 大盛り」のように 1 行で表示するための文字列。 */
    public String getOptionSummary() {
        if (options.isEmpty()) {
            return "";
        }
        // 個数が 2 以上のものだけ「ソース ×3」と出す。
        // 1 個のときに ×1 と書くと、いまある注文の見え方まで変わってしまう
        return options.stream()
                .map(o -> o.getQuantity() > 1
                        ? o.getChoiceName() + " ×" + o.getQuantity()
                        : o.getChoiceName())
                .collect(Collectors.joining(" / "));
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    void setOrder(Order order) {
        this.order = order;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getMenuItemName() {
        return menuItemName;
    }

    public int getBasePrice() {
        return basePrice;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getLineTotal() {
        return lineTotal;
    }

    public int getCookMinutes() {
        return cookMinutes;
    }

    public List<OrderLineOption> getOptions() {
        return options;
    }
}
