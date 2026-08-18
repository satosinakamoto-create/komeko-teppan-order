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
 *   小計            = キャンセル以外の注文の合計（税込）
 *   テーブルチャージ  = 単価 × 人数
 *   深夜料金の対象額  = 深夜帯に出した注文の合計
 *                    ＋ 深夜帯に着席していればテーブルチャージ
 *   深夜料金         = 対象額 × 割増率            ← 1円未満は切り捨て
 *   ────────────────────────────────
 *   ご請求額         = 小計 + テーブルチャージ + 深夜料金
 * </pre>
 *
 * <p><b>深夜料金は注文ごとに、その注文が出された時刻で決まります。</b>
 * 同じ伝票に通常料金の注文と深夜料金の注文が混在します。
 * 詳しくは {@link #recalculate(LateNightPolicy)} を読んでください。
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

    /**
     * 深夜料金が実際にかかったか（＝ {@link #lateNightAmount} が 1 円以上か）。
     *
     * <p>画面はこれを見て「深夜料金」の行を出すかどうかを決めます。
     * 「対象の注文があったか」ではなく<b>「金額が出たか」</b>にしてあるのは、
     * 対象額が 9 円以下だと 10% の割増が切り捨てで 0 円になり、
     * 「深夜料金（10%） ¥0」という無意味な行が伝票に出てしまうためです。
     */
    @Column(nullable = false)
    private boolean lateNightApplied;

    /**
     * スタッフが深夜料金を免除したか。
     *
     * <p><b>なぜ「免除した」ことを伝票に覚えさせるのか</b><br>
     * 深夜料金をかけるかどうかは、ふだんは注文時刻から自動で決まります。
     * ところがスタッフが会計画面でチェックを外して免除した場合、
     * それは<b>計算では再現できない、人の判断</b>です。
     * 覚えておかないと、次に金額を計算し直した瞬間に免除が消えてしまいます。
     *
     * <p>実際、これを持たせる前は<b>会計を取り消して開け直す（reopen）だけで
     * 免除が黙って復活していました</b>。
     * 人数を直したくて開け直したら、お客さまに伝えた金額と違う額に戻っていた、
     * ということが起こります。金額は上がる方向なので、そのまま請求すると事故です。
     *
     * <p>スタッフがもう一度チェックを入れて締めれば false に戻り、
     * 通常どおり注文時刻のルールで計算されます。
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean lateNightWaived;

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
     * <p><b>深夜料金は「注文時刻」で決まります（会計時刻ではありません）。</b><br>
     * 22:00 に頼んだ品は通常料金、23:30 に頼んだ品は深夜料金、というように
     * <b>同じ伝票の中で混在します</b>。だから注文を 1 件ずつ見て、
     * 深夜帯に出たものだけを割増の対象に積み上げます。
     *
     * <pre>
     *   20:00 着席 4名（チャージ ¥450 × 4 = ¥1,800）
     *   22:00 注文 ¥5,000  → 通常料金
     *   23:30 注文 ¥3,000  → 深夜料金の対象
     *
     *   割増の対象 = ¥3,000（チャージは 20:00 着席なので対象外）
     *   深夜料金   = ¥3,000 × 10% = ¥300
     *   ご請求     = 5,000 + 3,000 + 1,800 + 300 = ¥10,100
     * </pre>
     *
     * <p><b>会計時刻で判定してはいけない理由</b><br>
     * 会計時刻で伝票全体を判定すると、22:00 に注文を終えたお客さまが
     * 23:00 過ぎまで話し込んだだけで、注文すべてに割増がかかってしまいます。
     * 逆に 23:30 に注文して 5:00 過ぎに帰れば割増ゼロになります。
     * どちらも「深夜に出した品に割増する」という趣旨と合いません。
     * （2026-08-18 まで会計時刻で判定していました。実店舗の運用と違っていたので直しました）
     *
     * <p>テーブルチャージは注文ではないので、<b>着席時刻</b>で判定します。
     * 深夜に席を使い始めた卓のぶんだけ割増になります。
     *
     * @param policy 深夜料金をかけるかどうかの判定役。
     *               ふつうは {@code shopSetting::isLateNight}。
     *               スタッフが免除したときは {@link LateNightPolicy#NONE}
     */
    public void recalculate(LateNightPolicy policy) {
        // スタッフが免除した伝票は、以後どんな方針を渡されても割増しない。
        // 会計を取り消して開け直したときに、免除が黙って復活しないようにするため
        LateNightPolicy effective = lateNightWaived ? LateNightPolicy.NONE : policy;

        int subtotal = 0;
        int lateNightBase = 0;
        for (Order order : orders) {
            // キャンセルされた注文は請求しない
            if (order.getStatus() == OrderStatus.CANCELED) {
                continue;
            }
            subtotal += order.getTotalAmount();
            // ここが要。伝票の時刻でも「いま」でもなく、その注文が出された時刻で判定する。
            // ただしスタッフが「この注文は対象外」と指定したものは飛ばす
            // （打ち直しで時刻が新しくなった注文を救うため。Order#lateNightExempt 参照）
            if (!order.isLateNightExempt() && effective.appliesAt(order.getCreatedAt())) {
                lateNightBase += order.getTotalAmount();
            }
        }
        this.subtotalAmount = subtotal;
        this.tableChargeAmount = tableChargePerGuest * guestCount;

        // チャージは「席を使い始めた時刻」＝ 着席時刻で判定する
        if (effective.appliesAt(openedAt)) {
            lateNightBase += tableChargeAmount;
        }

        // 1 円未満は切り捨て（お客さんに有利な方向に丸める）。
        // 対象額を合計してから 1 回だけ割増を計算する。
        // 注文ごとに割増して足すと、切り捨てが注文の件数だけ効いて
        // 「同じ品を 1 回で頼むか 2 回に分けるか」で金額が変わってしまう。
        this.lateNightAmount = lateNightSurchargePercent > 0
                ? (int) ((long) lateNightBase * lateNightSurchargePercent / 100)
                : 0;
        // 「対象があったか」ではなく「金額が出たか」。
        // 対象額 9 円以下だと 10% が切り捨てで 0 円になり、¥0 の行が伝票に出てしまう
        this.lateNightApplied = lateNightAmount > 0;

        this.totalAmount = subtotalAmount + tableChargeAmount + lateNightAmount;
        this.taxAmount = TaxCalculator.includedTax(totalAmount, taxRatePercent);
    }

    /** 会計を締める。以後は追加注文できない。 */
    public void close(LocalDateTime at, LateNightPolicy policy, String staffName, String note) {
        recalculate(policy);
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

    /**
     * 深夜料金を<b>付けた</b>場合のご請求額。
     *
     * <p>深夜料金は注文時刻ごとに決まるので、「付けた場合いくらか」は
     * ここで計算し直すのではなく、{@link #recalculate(LateNightPolicy)} が
     * すでに出した {@link #lateNightAmount} をそのまま使います。
     * 開いている伝票は表示のたびに店舗設定のルールで計算し直されているため、
     * この値が「ルールどおりに付けた場合」の答えになります。
     *
     * <p>以前はここで「小計＋チャージの全額 × 割増率」を計算していましたが、
     * それは会計時刻で伝票全体を判定していた頃の名残です。
     * いまその式を使うと、22:00 の注文にも割増が乗った金額が画面に出てしまいます。
     */
    public int getTotalWithLateNight() {
        return subtotalAmount + tableChargeAmount + lateNightAmount;
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

    /**
     * 着席時刻を差し替える。<b>テスト専用</b>。
     *
     * <p>テーブルチャージに深夜料金がかかるかは着席時刻で決まるので、
     * 「20:00 に案内した卓」と「23:30 に案内した卓」をテストで作り分ける必要があります。
     * 理由と、public にしていない意図は {@link Order#setCreatedAtForTest} と同じです。
     */
    void setOpenedAtForTest(LocalDateTime openedAt) {
        this.openedAt = openedAt;
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

    public boolean isLateNightWaived() {
        return lateNightWaived;
    }

    /**
     * スタッフによる深夜料金の免除を設定する。
     *
     * <p>{@code true} にすると、以後この伝票には深夜料金がかかりません
     * （会計を取り消して開け直しても維持されます）。
     * {@code false} に戻せば、通常どおり注文時刻のルールで計算されます。
     * 呼ぶのは会計処理（{@code TableService#closeSession}）だけです。
     */
    public void setLateNightWaived(boolean lateNightWaived) {
        this.lateNightWaived = lateNightWaived;
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
