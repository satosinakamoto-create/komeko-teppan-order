package jp.komeko.order.domain;

import jakarta.persistence.*;

/**
 * 注文明細に付いたオプション 1 つ（スナップショット）。
 */
@Entity
@Table(name = "order_line_option")
public class OrderLineOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    /** 元の選択肢 ID（集計用）。 */
    private Long choiceId;

    /** 注文時点のグループ名（例: トッピング）。 */
    @Column(length = 40)
    private String groupName;

    /** 注文時点の選択肢名（例: チーズ追加）。 */
    @Column(nullable = false, length = 40)
    private String choiceName;

    /** 注文時点の追加料金（税込・円）。 */
    @Column(nullable = false)
    private int extraPrice;

    protected OrderLineOption() {
    }

    public OrderLineOption(Long choiceId, String groupName, String choiceName, int extraPrice) {
        this.choiceId = choiceId;
        this.groupName = groupName;
        this.choiceName = choiceName;
        this.extraPrice = extraPrice;
    }

    public Long getId() {
        return id;
    }

    public OrderLine getOrderLine() {
        return orderLine;
    }

    void setOrderLine(OrderLine orderLine) {
        this.orderLine = orderLine;
    }

    public Long getChoiceId() {
        return choiceId;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getChoiceName() {
        return choiceName;
    }

    public int getExtraPrice() {
        return extraPrice;
    }
}
