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

    // ── 品ごとの取り消し（2026-09-22 追加）──────────────────────────
    //
    // ★ なぜ注文（Order）ではなく明細に持たせたか。
    //   注文はカートを一度に確定した単位なので、4 品まとめて 1 件になります。
    //   「1 品だけ違うものが来た」ときに注文ごと消すと、残り 3 品まで
    //   請求から落ちます。店主の指摘そのものです——
    //   「取り消すボタンが卓ごとだから、一個の商品だけ破棄の場合とかだとムリじゃん」。
    //
    // ★ 行は消さずに「取り消した」と印を付けるだけにしています。
    //   注文履歴と提供時間の集計は事実の記録なので、行ごと消すと
    //   「厨房は作ったのに伝票に無い」を後から説明できなくなります。

    // ── 品ごとの段階（2026-09-23 追加）──────────────────────────────
    //
    // ★ 注文（Order.status）とは別に持ちます。
    //   注文はカートを一度に確定した単位なので、4 品で 1 件になります。
    //   「たこ焼だけ焼き上がった」は注文の状態では表せません。
    //   詳しくは LineStage の説明を読んでください。

    /** いま どこまで進んだか。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineStage stage = LineStage.UNCOOKED;

    /**
     * 調理済みにした時刻。
     *
     * <p><b>調理済みレーンの並び順がこれで決まります。</b>
     * 注文からの経過時間ではありません。左のレーンは「何分待たせたか」、
     * 右のレーンは「焼き上がってから何分置いたか」で、見ている時計が違います。
     * 右に注文時刻を持ち込むと、皿に乗ったばかりの品が上に、
     * 8 分置かれた品が下に並びます。
     */
    @Column
    private java.time.LocalDateTime cookedAt;

    /** 卓へ運んだ時刻。 */
    @Column
    private java.time.LocalDateTime servedAt;

    /** 取り消したか。金額の計算から外れる（{@link Order#recalculate()}）。 */
    @Column(nullable = false)
    private boolean canceled;

    @Column
    private java.time.LocalDateTime canceledAt;

    /** 取り消した理由。列は 100 文字まで。 */
    @Column(length = 100)
    private String canceledReason;

    /**
     * 取り消したときに残数（在庫）を戻したか。
     *
     * <p>「まだ作っていない」なら戻し、「作った・出した（廃棄）」なら戻しません。
     * <b>どちらを選んだかを残すのは、原価の説明のためです。</b>
     * 廃棄は材料が減ったままなので、あとから「なぜ理論原価と合わないのか」を
     * 追えるようにしておきます。
     */
    @Column(nullable = false)
    private boolean stockReturned;

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

    /**
     * この品を取り消す。
     *
     * <p>二度呼んでも 1 回目の記録を残します。理由や在庫の扱いを
     * あとから静かに書き換えられると、原価の説明がつかなくなるためです。
     * 二重に在庫を戻さないための判定にも、呼び出し側がこの戻り値を使います。
     *
     * @param stockReturned 残数を戻したなら true（「まだ作っていない」を選んだとき）
     * @return 今回はじめて取り消したなら true
     */
    public boolean cancel(String reason, boolean stockReturned) {
        if (canceled) {
            return false;
        }
        this.canceled = true;
        this.canceledAt = java.time.LocalDateTime.now();
        this.canceledReason = reason;
        this.stockReturned = stockReturned;
        return true;
    }

    /**
     * 段階を進める／戻す。
     *
     * <p>許されていない移り先は突き返します（{@link LineStage#allowedNext()}）。
     * 画面はボタンを出していませんが、古いタブや直接 POST からは届くので、
     * <b>ここでも閉じておきます</b>。厨房の changeStatus と同じ考え方です。
     *
     * @throws IllegalStateException 取り消し済みの品を動かそうとしたとき、
     *                               または許されていない移り先を渡されたとき
     */
    public void moveTo(LineStage next) {
        if (canceled) {
            // 取り消した品は厨房ボードに出ないので、正規の操作では届きません。
            // 通してしまうと「取り消したのに提供済みになった」行ができます
            throw new IllegalStateException("取り消した品は動かせません：" + menuItemName);
        }
        if (stage == next) {
            return;   // 二度押し。何もしない（時刻を上書きしないこと）
        }
        if (!stage.canMoveTo(next)) {
            throw new IllegalStateException(
                    "「" + menuItemName + "」を " + stage.getLabel()
                            + " から " + next.getLabel() + " へは動かせません");
        }
        this.stage = next;
        // 時刻は「はじめてそこへ行った」ときだけ記録する。
        // 戻して進め直すと並び順が飛ぶので、上書きしない
        if (next == LineStage.COOKED && cookedAt == null) {
            this.cookedAt = java.time.LocalDateTime.now();
        } else if (next == LineStage.SERVED && servedAt == null) {
            this.servedAt = java.time.LocalDateTime.now();
        }
    }

    public LineStage getStage() {
        return stage;
    }

    public java.time.LocalDateTime getCookedAt() {
        return cookedAt;
    }

    public java.time.LocalDateTime getServedAt() {
        return servedAt;
    }

    /** 厨房ボードに出すか。取り消した品と、運び終えた品は出さない。 */
    public boolean isOnKitchenBoard() {
        return !canceled && stage.isOnKitchenBoard();
    }

    public boolean isCanceled() {
        return canceled;
    }

    public java.time.LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public String getCanceledReason() {
        return canceledReason;
    }

    public boolean isStockReturned() {
        return stockReturned;
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
