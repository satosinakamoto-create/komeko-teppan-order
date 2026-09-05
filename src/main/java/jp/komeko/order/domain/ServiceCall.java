package jp.komeko.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * お客さまからの呼び出し 1 件。
 *
 * <p>「スタッフを呼ぶ」「お会計をお願いする」を押したときに 1 行できます。
 * ホール画面に出て、スタッフが「対応した」を押すと片付きます。
 *
 * <p><b>なぜ注文（{@link Order}）に混ぜないのか</b><br>
 * 呼び出しには金額も個数もありません。
 * ¥0 の商品として注文に流すと、伝票に ¥0 の行が並び、
 * 売上画面の注文数にも 1 件として数えられます。
 * 「お会計をお願いします」が注文数に入ると、客単価の意味が濁ります。
 *
 * <p>一方で<b>持っていく物</b>（お水・おしぼり・取り皿・灰皿・塩コショウ・領収書）は
 * ¥0 の商品として注文に流しています。あちらには
 * 「焼く → 出す」に相当する進行があり、注文の状態遷移がそのまま使えるからです。
 * 同じ「サービス」の画面から出ていても、性質が違うので通り道を分けてあります。
 *
 * <p><b>消さずに残す</b><br>
 * 対応済みも行として残します。「何回呼ばれたか」「どれくらい待たせたか」は、
 * あとから店の動きを見直すときの材料になります。
 * 消してしまうと、忙しかった日ほど記録が残りません。
 */
@Entity
@Table(name = "service_call")
public class ServiceCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * どの伝票からの呼び出しか。
     *
     * <p>卓ではなく伝票に紐づけます。卓に紐づけると、
     * 会計して次のお客さまが座ったあとも同じ卓の呼び出しとして残り、
     * 「誰が呼んだのか」が分からなくなります。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TableSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceCallType type;

    /**
     * 営業日。
     *
     * <p>伝票からコピーします。深夜 0 時をまたいでも同じ営業日として数えるためで、
     * 注文（{@code Order.businessDate}）と同じ考え方です。
     */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 対応した時刻。null なら、まだ誰も向かっていない。 */
    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    /** 対応した人の名前。ログインしていないときは null。 */
    @Column(name = "handled_by", length = 40)
    private String handledBy;

    /** JPA が使う。 */
    protected ServiceCall() {
    }

    public ServiceCall(TableSession session, ServiceCallType type) {
        this.session = session;
        this.type = type;
        this.businessDate = session.getBusinessDate();
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 対応済みにする。
     *
     * <p>すでに対応済みなら何もしません。
     * ホール画面は数秒ごとに描き直されるので、
     * 二人が同時に押すことが普通に起こります。
     * あとから押した人の名前で上書きすると、
     * 「先に向かった人」の記録が消えてしまいます。
     */
    public void handle(String staffName) {
        if (handledAt != null) {
            return;
        }
        this.handledAt = LocalDateTime.now();
        this.handledBy = staffName;
    }

    public boolean isPending() {
        return handledAt == null;
    }

    /** 呼ばれてから何分たったか。対応済みなら対応までにかかった分数。 */
    public long getWaitedMinutes() {
        LocalDateTime end = handledAt != null ? handledAt : LocalDateTime.now();
        return java.time.Duration.between(createdAt, end).toMinutes();
    }

    // ── getter ──────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public TableSession getSession() {
        return session;
    }

    public ServiceCallType getType() {
        return type;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public String getHandledBy() {
        return handledBy;
    }
}
