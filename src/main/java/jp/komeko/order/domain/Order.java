package jp.komeko.order.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 注文（伝票 1 枚）。このシステムの中心となるエンティティ。
 *
 * <p><b>テーブル名を orders にしている理由</b><br>
 * {@code ORDER} は SQL の予約語（ORDER BY）なので、テーブル名に使うとエラーになります。
 * そのため {@code @Table(name = "orders")} で別名を付けています。よくある落とし穴です。
 *
 * <p><b>スナップショットの考え方</b><br>
 * 注文明細（{@link OrderLine}）には商品名と価格を「そのときの値」でコピーして保存します。
 * 商品マスタを参照するだけだと、あとで値上げしたときに過去の伝票の金額まで
 * 変わってしまうためです。会計まわりでは必ずスナップショットを取ります。
 *
 * <p><b>publicToken について</b><br>
 * お客さんは会員登録もログインもしません。そのかわり注文確定時に
 * 推測できないランダムな文字列を発行し、{@code /o/{token}} という URL で
 * 自分の注文状況だけを見られるようにしています（ケイパビリティ URL という方式）。
 * 連番の ID を URL に出すと、番号を変えるだけで他人の注文が覗けてしまいます。
 */
@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                // 同じ営業日に同じ注文番号が 2 つできないよう DB 側でも保証する
                @UniqueConstraint(name = "uk_orders_business_date_number",
                        columnNames = {"business_date", "order_number"})
        },
        indexes = {
                @Index(name = "idx_orders_status", columnList = "status"),
                @Index(name = "idx_orders_business_date", columnList = "business_date")
        }
)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 営業日。深夜営業を考慮して「暦の日付」とは別に持つ。
     * 例: 深夜 2 時の注文は前日の営業日として集計したい。
     */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** 営業日ごとの通し番号。お客さんを呼び出すときに使う番号。 */
    @Column(name = "order_number", nullable = false)
    private int orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.RECEIVED;

    /** お客さん専用の注文状況 URL に使うランダム文字列。 */
    @Column(nullable = false, unique = true, length = 36)
    private String publicToken;

    /** 呼び出し用のニックネーム（任意入力）。番号と併記すると呼びやすい。 */
    @Column(length = 20)
    private String customerName;

    /** お客さんからの要望（「ソース少なめ」など）。 */
    @Column(length = 200)
    private String note;

    /** 税込合計金額（円）。 */
    @Column(nullable = false)
    private int totalAmount;

    /** 上記に含まれる消費税額（内税・円）。レシート表示用。 */
    @Column(nullable = false)
    private int taxAmount;

    /** 注文時点の税率（%）。あとで税率が変わっても過去の伝票が壊れないよう保存する。 */
    @Column(nullable = false)
    private int taxRatePercent;

    /** 受付時に算出した調理時間の見込み（分）。 */
    @Column(nullable = false)
    private int estimatedCookMinutes;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime cookingStartedAt;
    private LocalDateTime readyAt;
    private LocalDateTime completedAt;
    private LocalDateTime canceledAt;

    @Column(length = 100)
    private String canceledReason;

    /** 誰が状態を進めたかの記録（スタッフ名）。監査用。 */
    @Column(length = 40)
    private String lastHandledBy;

    protected Order() {
    }

    public Order(LocalDate businessDate, int orderNumber, int taxRatePercent) {
        this.businessDate = businessDate;
        this.orderNumber = orderNumber;
        this.taxRatePercent = taxRatePercent;
        this.publicToken = UUID.randomUUID().toString();
        this.status = OrderStatus.RECEIVED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    // ── ビジネスロジック ──────────────────────────────────────────

    /**
     * 明細を追加する。双方向関連の両側を必ずセットするのがポイント。
     * （片側だけだと保存時に order_id が NULL になって落ちる）
     */
    public void addLine(OrderLine line) {
        lines.add(line);
        line.setOrder(this);
    }

    /** 明細から合計金額・税額・調理見込みを再計算する。 */
    public void recalculate() {
        int total = 0;
        int cook = 0;
        for (OrderLine line : lines) {
            total += line.getLineTotal();
            // 同じ商品を n 個頼んでも鉄板には一緒に乗るので、調理時間は「最大値」ではなく
            // 「品目ごとの調理時間 × 個数」の合計を上限とみなす（実運用に合わせて調整可）
            cook += line.getCookMinutes() * line.getQuantity();
        }
        this.totalAmount = total;
        this.taxAmount = TaxCalculator.includedTax(total, taxRatePercent);
        this.estimatedCookMinutes = cook;
        touch();
    }

    /**
     * 状態を遷移させる。許可されていない遷移は例外にする。
     *
     * @param next      次の状態
     * @param handledBy 操作したスタッフ名（null 可）
     * @throws IllegalStateException 許可されていない遷移のとき
     */
    public void changeStatus(OrderStatus next, String handledBy) {
        if (this.status == next) {
            return; // 二度押し対策。同じ状態への遷移は黙って無視する
        }
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "「%s」から「%s」へは変更できません".formatted(status.getStaffLabel(), next.getStaffLabel()));
        }
        this.status = next;
        this.lastHandledBy = handledBy;
        LocalDateTime now = LocalDateTime.now();
        switch (next) {
            case COOKING -> this.cookingStartedAt = now;
            case READY -> this.readyAt = now;
            case COMPLETED -> this.completedAt = now;
            case CANCELED -> this.canceledAt = now;
            default -> { }
        }
        this.updatedAt = now;
    }

    public void cancel(String reason, String handledBy) {
        changeStatus(OrderStatus.CANCELED, handledBy);
        this.canceledReason = reason;
    }

    /** お客さん自身がキャンセルしてよいか（まだ焼き始めていないときだけ）。 */
    public boolean isCustomerCancelable() {
        return status == OrderStatus.RECEIVED;
    }

    /** 受付からの経過時間（分）。厨房画面で「◯分経過」と出すのに使う。 */
    public long getElapsedMinutes() {
        LocalDateTime end = switch (status) {
            case COMPLETED -> completedAt;
            case CANCELED -> canceledAt;
            default -> LocalDateTime.now();
        };
        if (end == null) {
            end = LocalDateTime.now();
        }
        return Duration.between(createdAt, end).toMinutes();
    }

    /** 明細の合計点数。 */
    public int getTotalQuantity() {
        return lines.stream().mapToInt(OrderLine::getQuantity).sum();
    }

    /** 税抜相当額（表示用）。 */
    public int getNetAmount() {
        return totalAmount - taxAmount;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * DB へ更新する直前に自動で呼ばれるコールバック。
     * updatedAt の更新漏れを防ぐための保険です。
     */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public int getTaxAmount() {
        return taxAmount;
    }

    public int getTaxRatePercent() {
        return taxRatePercent;
    }

    public int getEstimatedCookMinutes() {
        return estimatedCookMinutes;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getCookingStartedAt() {
        return cookingStartedAt;
    }

    public LocalDateTime getReadyAt() {
        return readyAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public String getCanceledReason() {
        return canceledReason;
    }

    public String getLastHandledBy() {
        return lastHandledBy;
    }
}
