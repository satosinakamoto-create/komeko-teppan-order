package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 棚卸し・廃棄・まかないの記録。在庫計算の起点と補正。
 *
 * <p><b>この行は訂正しません。</b>間違えたら、打ち消す行をもう 1 本足します。
 * 「あの日は 1200g と数えた」という事実は、あとから見て正しくなくても事実です。
 * 書き換えてしまうと、なぜ在庫が合わなかったのかを追う手がかりが消えます。
 * 注文時刻を書き換えられないようにしてあるのと同じ考え方です。
 *
 * @see StocktakeType 起点（RESET）と増減（ADJUST）の違い
 */
@Entity
@Table(name = "stocktake", indexes = {
        @Index(name = "idx_stocktake_ingredient", columnList = "ingredient_id, taken_on")
})
public class Stocktake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    /**
     * いつ数えた／いつ捨てたか（日付）。
     *
     * <p><b>時刻ではなく日付で持ちます。</b>在庫の増減は仕入れ（日付）や
     * 注文（営業日）と突き合わせるので、片方だけ時刻の細かさを持っていても
     * 比べようがありません。粒度をそろえるほうが計算の意味がはっきりします。
     */
    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    /** 記録した時刻。事実の記録であって、計算には使わない。 */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private StocktakeType type;

    /**
     * 数量。RESET なら実測した絶対量、ADJUST なら増減量（減らすときは負）。
     */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private StocktakeReason reason = StocktakeReason.STOCKTAKE;

    @Column(length = 200)
    private String memo;

    /** 記録した人（staff_user.id）。 */
    @Column(name = "created_by")
    private Long createdBy;

    protected Stocktake() {
        // JPA 用
    }

    public Stocktake(Ingredient ingredient, LocalDate takenOn, StocktakeType type,
                     BigDecimal quantity, StocktakeReason reason, LocalDateTime recordedAt) {
        this.ingredient = ingredient;
        this.takenOn = takenOn;
        this.type = type;
        this.quantity = quantity;
        this.reason = reason;
        this.recordedAt = recordedAt;
    }

    // ── getter / setter ──

    public Long getId() {
        return id;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public StocktakeType getType() {
        return type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public StocktakeReason getReason() {
        return reason;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
