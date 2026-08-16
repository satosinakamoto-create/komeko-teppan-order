package jp.komeko.order.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 卓の「1 回の来店」＝ <b>伝票</b>。
 *
 * <p>イートインでは、1 組のお客さんが席についてから会計するまでの間に
 * 何度も注文します。その注文をぜんぶ束ねて、最後にまとめて会計するのが伝票です。
 *
 * <pre>
 *   DiningTable（3番テーブル）
 *        │
 *        ├── TableSession #1  19:02 来店 4名  … CLOSED（21:30 会計 ¥12,400）
 *        └── TableSession #2  21:45 来店 2名  … OPEN
 *                 ├── Order #101  生ビール2、たこ焼8個
 *                 ├── Order #104  お好み焼き、ハイボール   ← 追加注文
 *                 └── Order #109  レモンサワー2          ← 追加注文
 * </pre>
 *
 * <p><b>金額の計算</b>
 * <pre>
 *   小計          = キャンセル以外の注文の合計（税込）
 *   テーブルチャージ = 単価 × 人数
 *   深夜料金       = (小計 + テーブルチャージ) × 割増率
 *   ─────────────────────────────
 *   ご請求額       = 小計 + テーブルチャージ + 深夜料金
 * </pre>
 *
 * <p>単価・割増率は<b>来店時点の設定をコピーして保存</b>します。
 * あとから店舗設定を変えても、過去の伝票の金額が変わらないようにするためです。
 */
@Entity
@jakarta.persistence.Table(
        name = "table_session",
        indexes = {
                @Index(name = "idx_session_status", columnList = "status"),
                @Index(name = "idx_session_business_date", columnList = "business_date")
        }
)
public class TableSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dining_table_id", nullable = false)
    private DiningTable diningTable;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.OPEN;

    /** 人数。テーブルチャージの計算に使う。 */
    @Column(nullable = false)
    private int guestCount = 1;

    @Column(nullable = false)
    private LocalDateTime openedAt = LocalDateTime.now();

    private LocalDateTime closedAt;

    /** 会計したスタッフの表示名。 */
    @Column(length = 40)
    private String closedBy;

    @OneToMany(mappedBy = "session")
    @OrderBy("createdAt ASC, id ASC")
    @BatchSize(size = 50)
    private List<Order> orders = new ArrayList<>();

    // ── 来店時点の設定のコピー（あとから設定を変えても伝票が変わらないように） ──

    @Column(nullable = false)
    private int taxRatePercent;

    @Column(nullable = false)
    private int tableChargePerGuest;

    @Column(nullable = false)
    private int lateNightSurchargePercent;

    // ── 計算結果（画面表示と会計のたびに更新される） ──

    /** 注文の合計（税込）。 */
    @Column(nullable = false)
    private int subtotalAmount;

    /** テーブルチャージの合計。 */
    @Column(nullable = false)
    private int tableChargeAmount;

    /** 深夜料金。 */
    @Column(nullable = false)
    private int lateNightAmount;

    /** ご請求額（小計＋チャージ＋深夜料金）。 */
    @Column(nullable = false)
    private int totalAmount;

    /** ご請求額に含まれる消費税。 */
    @Column(nullable = false)
    private int taxAmount;

    /** 深夜料金を適用したか。スタッフが手動で外すこともできる。 */
    @Column(nullable = false)
    private boolean lateNightApplied;

    /** 会計時のメモ（割引理由など）。 */
    @Column(length = 200)
    private String note;

    protected TableSession() {
    }

    public TableSession(DiningTable diningTable, LocalDate businessDate, int guestCount, ShopSetting setting) {
        this.diningTable = diningTable;
        this.businessDate = businessDate;
        this.guestCount = Math.max(1, guestCount);
        this.openedAt = LocalDateTime.now();
        this.status = SessionStatus.OPEN;

        this.taxRatePercent = setting.getTaxRatePercent();
        this.tableChargePerGuest = setting.getTableChargePerGuest();
        this.lateNightSurchargePercent = setting.getLateNightSurchargePercent();
    }

    // ========================================================================
    //  金額の計算
    // ========================================================================

    /**
     * 伝票の金額を計算し直す。
     *
     * <p>注文が増えたとき、人数が変わったとき、会計するときに呼びます。
     *
     * @param at            計算の基準時刻（深夜料金の判定に使う）
     * @param applyLateNight 深夜料金を適用するか（スタッフが手動で外せる）
     */
    public void recalculate(LocalDateTime at, boolean applyLateNight) {
        int subtotal = 0;
        for (Order order : orders) {
            // キャンセルされた注文は請求しない
            if (order.getStatus() == OrderStatus.CANCELED) {
                continue;
            }
            subtotal += order.getTotalAmount();
        }
        this.subtotalAmount = subtotal;
        this.tableChargeAmount = tableChargePerGuest * guestCount;

        int base = subtotalAmount + tableChargeAmount;
        this.lateNightApplied = applyLateNight && lateNightSurchargePercent > 0;
        // 1 円未満は切り捨て（お客さんに有利な方向に丸める）
        this.lateNightAmount = lateNightApplied
                ? (int) ((long) base * lateNightSurchargePercent / 100)
                : 0;

        this.totalAmount = base + lateNightAmount;
        this.taxAmount = TaxCalculator.includedTax(totalAmount, taxRatePercent);
    }

    /** 会計を締める。以後は追加注文できない。 */
    public void close(LocalDateTime at, boolean applyLateNight, String staffName, String note) {
        recalculate(at, applyLateNight);
        this.status = SessionStatus.CLOSED;
        this.closedAt = at;
        this.closedBy = staffName;
        this.note = note;
    }

    /** 会計を取り消して伝票を開け直す（誤会計のリカバリ）。 */
    public void reopen() {
        this.status = SessionStatus.OPEN;
        this.closedAt = null;
        this.closedBy = null;
    }

    // ========================================================================
    //  表示用
    // ========================================================================

    public boolean isOpen() {
        return status == SessionStatus.OPEN;
    }

    /** 追加注文を受け付けられるか。 */
    public boolean isOrderable() {
        return isOpen();
    }

    /** 滞在時間（分）。 */
    public long getStayMinutes() {
        LocalDateTime end = (closedAt != null) ? closedAt : LocalDateTime.now();
        return Duration.between(openedAt, end).toMinutes();
    }

    /** 注文の点数の合計（キャンセルを除く）。 */
    public int getTotalQuantity() {
        return orders.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELED)
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    /** キャンセルを除いた注文だけを返す（伝票の明細表示用）。 */
    public List<Order> getBillableOrders() {
        return orders.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELED)
                .toList();
    }

    /** まだ厨房で作っている品があるか（会計前の確認に使う）。 */
    public boolean hasPendingOrders() {
        return orders.stream().anyMatch(o -> o.getStatus().isActive());
    }

    /** 税抜相当額（表示用）。 */
    public int getNetAmount() {
        return totalAmount - taxAmount;
    }

    /**
     * 深夜料金を<b>付けなかった</b>場合のご請求額。
     *
     * <p>会計画面でスタッフが深夜料金のチェックを外したときに、
     * 「いくらになるか」をその場で出すために使います。
     *
     * <p>計算式を画面側に書くとこのクラスと二重管理になってしまうので、
     * <b>「付けた場合」と「付けなかった場合」の両方をここで出しておき</b>、
     * 画面はどちらを表示するか選ぶだけ、という形にしています。
     */
    public int getTotalWithoutLateNight() {
        return subtotalAmount + tableChargeAmount;
    }

    /** 深夜料金を<b>付けた</b>場合のご請求額。 */
    public int getTotalWithLateNight() {
        int base = subtotalAmount + tableChargeAmount;
        if (lateNightSurchargePercent <= 0) {
            return base;
        }
        return base + (int) ((long) base * lateNightSurchargePercent / 100);
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public DiningTable getDiningTable() {
        return diningTable;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public int getGuestCount() {
        return guestCount;
    }

    public void setGuestCount(int guestCount) {
        this.guestCount = Math.max(1, guestCount);
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public int getTaxRatePercent() {
        return taxRatePercent;
    }

    public int getTableChargePerGuest() {
        return tableChargePerGuest;
    }

    public int getLateNightSurchargePercent() {
        return lateNightSurchargePercent;
    }

    public int getSubtotalAmount() {
        return subtotalAmount;
    }

    public int getTableChargeAmount() {
        return tableChargeAmount;
    }

    public int getLateNightAmount() {
        return lateNightAmount;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public int getTaxAmount() {
        return taxAmount;
    }

    public boolean isLateNightApplied() {
        return lateNightApplied;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
