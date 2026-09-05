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

    /** 注文時点の追加料金（税込・円）。<b>1 個あたり</b>の金額。 */
    @Column(nullable = false)
    private int extraPrice;

    /**
     * この選択肢をいくつ選んだか。
     *
     * <p>ふつうは 1 です。{@link OptionGroup#isAllowDuplicate()} が立っているグループでだけ
     * 2 以上になり得ます（「ソース 3 つ・ごま油 1 つ」のような頼まれ方）。
     *
     * <p><b>同じ行を 3 本作るのではなく、1 行に個数を持たせています。</b>
     * 厨房のチケットに同じ字が 3 行並ぶのを避けたいのと、
     * 「ソース ×3」と 1 行で出したいためです。集計のたびにまとめ直す処理も要りません。
     *
     * <p>金額は {@link #getSubtotal()}（{@code extraPrice × quantity}）で出します。
     * {@code extraPrice} 単体は 1 個あたりの額なので、そのまま足さないこと。
     *
     * <p>{@code columnDefinition} に既定値を書いてあるのは、
     * データの入った DB に NOT NULL の列を足せるようにするためです
     * （2026-09-05 に {@code monthly_rent} で実際に踏んだ罠。
     * {@code ShopSetting} のコメント参照）。
     */
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    private int quantity = 1;

    protected OrderLineOption() {
    }

    /** 個数 1 で作る。これまでどおりの呼び方。 */
    public OrderLineOption(Long choiceId, String groupName, String choiceName, int extraPrice) {
        this(choiceId, groupName, choiceName, extraPrice, 1);
    }

    public OrderLineOption(Long choiceId, String groupName, String choiceName,
                           int extraPrice, int quantity) {
        this.choiceId = choiceId;
        this.groupName = groupName;
        this.choiceName = choiceName;
        this.extraPrice = extraPrice;
        // 0 や負の個数は「選んでいない」と区別が付かないので 1 に倒す
        this.quantity = Math.max(1, quantity);
    }

    /** このオプションぶんの追加料金（1 個あたり × 個数）。 */
    public int getSubtotal() {
        return extraPrice * quantity;
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

    public int getQuantity() {
        return quantity;
    }

    /** 1 個あたりの追加料金。合計は {@link #getSubtotal()} を使うこと。 */
    public int getExtraPrice() {
        return extraPrice;
    }
}
