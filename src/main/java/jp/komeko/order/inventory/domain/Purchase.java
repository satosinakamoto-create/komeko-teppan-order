package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;
import jp.komeko.order.domain.TaxCalculator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * レシート 1 枚ぶんの仕入れ・経費の記録。
 *
 * <p><b>この 1 クラスが背負っているもの</b>
 * <ul>
 *   <li>お金の記録（いくら払ったか、何に払ったか）</li>
 *   <li>消費税の記録（税率ごとの内訳、控除できるか）</li>
 *   <li>証憑としての記録（電子帳簿保存法。紙を捨ててよくするための条件）</li>
 * </ul>
 *
 * <p><b>電子帳簿保存法のために守っていること</b><br>
 * 紙のレシートを捨てるには、画像がただ保存されているだけでは足りません。
 * 「あとから書き換えられていないこと」を示せる必要があります。そのために:
 * <ul>
 *   <li>{@link #storedAt} はサーバの時計で 1 度だけ入れ、以後変えない</li>
 *   <li>削除は {@link #deleted} を立てるだけ。行は消さない（消した記録も検索できる必要がある）</li>
 *   <li>検索の 3 項目（取引年月日・取引金額・取引先）を必ず列として持つ</li>
 * </ul>
 *
 * <p><b>合計金額を明細の合算にしない理由</b><br>
 * {@link #totalAmount} は<b>レシートに印字されていた合計</b>です。
 * 明細を足した額と 1 円ずれることがあります（端数処理、値引き行、読み取れなかった行）。
 * 紙と画面が合わないシステムは、入力する人の信頼を一度で失います。
 * だから合計は合計として持ち、ずれは {@link #lineTotalMismatch()} で見えるようにします。
 */
@Entity
@Table(name = "purchase", indexes = {
        // 検索 3 項目（電子帳簿保存法）に効く索引。
        @Index(name = "idx_purchase_purchased_on", columnList = "purchased_on"),
        @Index(name = "idx_purchase_store_name", columnList = "store_name")
})
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========================================================================
    //  検索 3 項目（電子帳簿保存法で必須）
    // ========================================================================

    /** 取引年月日。レシートに印字されている日付。 */
    @Column(name = "purchased_on", nullable = false)
    private LocalDate purchasedOn;

    /** 取引先名（店名）。 */
    @Column(name = "store_name", nullable = false, length = 120)
    private String storeName;

    /** レシートに印字されていた合計金額（税込・円）。 */
    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    // ========================================================================
    //  入力期限（電子帳簿保存法）
    // ========================================================================

    /**
     * 受領日。入力期限を数える起点。
     * ふつうは取引日と同じだが、月をまたいで届く請求書などでは別になる。
     */
    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    /**
     * システムに保存し終えた日時。<b>サーバの時計で 1 度だけ入れ、二度と変えない。</b>
     *
     * <p>「入力期限内に格納したこと」を示す値なので、
     * 利用者の端末の時計や、あとからの手入力に依存させてはいけません。
     */
    @Column(name = "stored_at", nullable = false, updatable = false)
    private LocalDateTime storedAt;

    /**
     * 紙と画像を見比べて同じだと確認した日時。null なら未確認。
     * ここが入って初めて「紙を捨ててよい」と表示する。
     */
    @Column(name = "equivalence_checked_at")
    private LocalDateTime equivalenceCheckedAt;

    /**
     * 紙の原本を保管し続ける必要があるか。
     * 入力期限を過ぎて登録された場合に立てる（その分はスキャナ保存に代えられない）。
     */
    @Column(name = "paper_retention_required", nullable = false)
    private boolean paperRetentionRequired = false;

    // ========================================================================
    //  消費税（インボイス制度）
    // ========================================================================

    /** OCR が読んだ登録番号の生文字列。整形前のものを証跡として残す。 */
    @Column(name = "reg_number_raw", length = 60)
    private String regNumberRaw;

    /** 正規化した登録番号（T + 13 桁）。読めなければ null。 */
    @Column(name = "reg_number", length = 14)
    private String regNumber;

    /** 登録番号をどこまで確かめたか。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "reg_verify_status", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private RegistrationVerifyStatus regVerifyStatus = RegistrationVerifyStatus.NONE;

    /** 証憑としての区分。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 30, columnDefinition = "varchar(30)")
    private EvidenceType evidenceType = EvidenceType.SIMPLIFIED_INVOICE;

    /**
     * この仕入れに適用した控除率（%）のスナップショット。
     *
     * <p>マスタから引いた値を<b>写して持ちます</b>。あとで経過措置の率が変わっても、
     * 過去の記録が動かないようにするためです（注文が税率を写して持つのと同じ考え方）。
     */
    @Column(name = "deduction_rate_percent", nullable = false)
    private int deductionRatePercent = 100;

    // ========================================================================
    //  そのほか
    // ========================================================================

    /** 支払い方法。仕訳の貸方（現金か未払金か）が変わるので記録する。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    /** レシート画像の公開パス（例: /uploads/xxxx.jpg）。手入力なら null。 */
    @Column(name = "image_path", length = 200)
    private String imagePath;

    /**
     * AI 読取の生の結果（JSON）。読み違いを後から検証するために残す。
     *
     * <p>{@code @Lob} ではなく長さ指定の {@code varchar} にしているのは、
     * H2 と PostgreSQL で {@code @Lob} の実際の型が分かれ（CLOB / text）、
     * マイグレーションの SQL を 1 本で両対応させにくいためです。
     * レシート 1 枚ぶんの JSON はどんなに長くてもこの範囲に収まります。
     */
    @Column(name = "ocr_json", length = 20000)
    private String ocrJson;

    @Column(length = 300)
    private String memo;

    /** 登録した人（staff_user.id）。 */
    @Column(name = "created_by")
    private Long createdBy;

    // ── 税理士の確認（V5 で追加） ──

    /**
     * 税理士が帳簿として確認した日時。null なら未確認。
     *
     * <p>店の {@link #equivalenceCheckedAt}（紙と見比べた）とは別物です。
     * あちらは電子帳簿保存法の同等確認で店の人がやる作業、
     * こちらは<b>第二の目</b>としての確認。両方あって初めて
     * 「店も税理士も見た」と言えます。
     */
    @Column(name = "accountant_checked_at")
    private LocalDateTime accountantCheckedAt;

    /** 確認した税理士（staff_user.id）。 */
    @Column(name = "accountant_checked_by")
    private Long accountantCheckedBy;

    /** 税理士の申し送り（「これは何の支出か店主に確認」など）。 */
    @Column(name = "accountant_note", length = 300)
    private String accountantNote;

    // ── 論理削除（物理削除は絶対にしない） ──

    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "delete_reason", length = 200)
    private String deleteReason;

    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<PurchaseLine> lines = new ArrayList<>();

    protected Purchase() {
        // JPA 用
    }

    public Purchase(LocalDate purchasedOn, LocalDate receivedOn, String storeName,
                    int totalAmount, PaymentMethod paymentMethod, LocalDateTime storedAt) {
        this.purchasedOn = purchasedOn;
        this.receivedOn = receivedOn != null ? receivedOn : purchasedOn;
        this.storeName = storeName;
        this.totalAmount = totalAmount;
        this.paymentMethod = paymentMethod != null ? paymentMethod : PaymentMethod.CASH;
        this.storedAt = storedAt;
    }

    /** 明細行を足す。双方向の関連はここで張るので、呼び出し側で setPurchase しない。 */
    public void addLine(PurchaseLine line) {
        line.setPurchase(this);
        lines.add(line);
    }

    // ========================================================================
    //  集計（保存せず、必要なときに数える）
    // ========================================================================

    /** 明細を足し上げた額（税込）。{@link #totalAmount} とずれることがある。 */
    public int lineTotal() {
        int sum = 0;
        for (PurchaseLine line : lines) {
            sum += line.getAmount();
        }
        return sum;
    }

    /** レシートの合計と明細の合算がずれている額（0 なら一致）。 */
    public int lineTotalMismatch() {
        return totalAmount - lineTotal();
    }

    /** 明細の税抜合計。 */
    public int netTotal() {
        return totalAmount() - taxTotal();
    }

    /** 明細の合計（税込）。書類合計 {@code totalAmount} との突き合わせにも使う。 */
    private int totalAmount() {
        int sum = 0;
        for (PurchaseLine line : lines) {
            sum += line.getAmount();
        }
        return sum;
    }

    /** 明細の消費税合計。 */
    public int taxTotal() {
        // ── 印字された税額がある行は、その値をそのまま信じる ──
        int printed = 0;
        // ── 印字の無い行は、税率ごとに束ねてから 1 回だけ逆算する ──
        //
        // 行ごとに逆算して足すと、切り捨てが行数ぶん効いて紙とずれる。
        // 例: 8% の 101 円が 3 行 → 行ごとだと 7+7+7=21 円、
        //     束ねてからだと 303×8÷108=22 円。適格請求書の端数処理は
        //     「1 枚につき税率ごとに 1 回」が制度上のルールで、レシートの
        //     印字もその方式なので、束ねるほうが紙と合う。
        // 深夜料金で踏んだ「切り捨ては合計してから 1 回」と同じ教訓。
        java.util.Map<Integer, Integer> unprintedByRate = new java.util.TreeMap<>();
        for (PurchaseLine line : lines) {
            if (line.getTaxAmount() != null) {
                printed += line.getTaxAmount();
            } else {
                unprintedByRate.merge(line.getTaxRatePercent(), line.getAmount(), Integer::sum);
            }
        }
        int computed = 0;
        for (java.util.Map.Entry<Integer, Integer> group : unprintedByRate.entrySet()) {
            computed += TaxCalculator.includedTax(group.getValue(), group.getKey());
        }
        return printed + computed;
    }

    /** 食材（原価率の分子）の税込合計。 */
    public int foodCostTotalIncludingTax() {
        int sum = 0;
        for (PurchaseLine line : lines) {
            if (line.getCategory().isFoodCost()) {
                sum += line.getAmount();
            }
        }
        return sum;
    }

    /** 食材（原価率の分子）の税抜合計。 */
    public int foodCostTotalNet() {
        int sum = 0;
        for (PurchaseLine line : lines) {
            if (line.getCategory().isFoodCost()) {
                sum += line.netAmount();
            }
        }
        return sum;
    }

    /** 紙の原本を捨ててよい状態か（同等確認済みで、期限内に登録できている）。 */
    public boolean canDiscardPaper() {
        return equivalenceCheckedAt != null && !paperRetentionRequired;
    }

    /** 同等確認を記録する。 */
    public void markEquivalenceChecked(LocalDateTime at) {
        this.equivalenceCheckedAt = at;
    }

    /** 論理削除する。行は消さず、消したという事実を残す。 */
    public void markDeleted(LocalDateTime at, String reason) {
        this.deleted = true;
        this.deletedAt = at;
        this.deleteReason = reason;
    }

    // ── getter / setter ──

    public Long getId() {
        return id;
    }

    public LocalDate getPurchasedOn() {
        return purchasedOn;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public String getStoreName() {
        return storeName;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public LocalDateTime getStoredAt() {
        return storedAt;
    }

    public LocalDateTime getEquivalenceCheckedAt() {
        return equivalenceCheckedAt;
    }

    public boolean isPaperRetentionRequired() {
        return paperRetentionRequired;
    }

    public void setPaperRetentionRequired(boolean paperRetentionRequired) {
        this.paperRetentionRequired = paperRetentionRequired;
    }

    public String getRegNumberRaw() {
        return regNumberRaw;
    }

    public void setRegNumberRaw(String regNumberRaw) {
        this.regNumberRaw = regNumberRaw;
    }

    public String getRegNumber() {
        return regNumber;
    }

    public void setRegNumber(String regNumber) {
        this.regNumber = regNumber;
    }

    public RegistrationVerifyStatus getRegVerifyStatus() {
        return regVerifyStatus;
    }

    public void setRegVerifyStatus(RegistrationVerifyStatus regVerifyStatus) {
        this.regVerifyStatus = regVerifyStatus;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public int getDeductionRatePercent() {
        return deductionRatePercent;
    }

    public void setDeductionRatePercent(int deductionRatePercent) {
        this.deductionRatePercent = deductionRatePercent;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getOcrJson() {
        return ocrJson;
    }

    public void setOcrJson(String ocrJson) {
        this.ocrJson = ocrJson;
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

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    public LocalDateTime getAccountantCheckedAt() {
        return accountantCheckedAt;
    }

    public Long getAccountantCheckedBy() {
        return accountantCheckedBy;
    }

    public String getAccountantNote() {
        return accountantNote;
    }

    /** 税理士が確認したことを記録する。 */
    public void markAccountantChecked(LocalDateTime at, Long staffId, String note) {
        this.accountantCheckedAt = at;
        this.accountantCheckedBy = staffId;
        if (note != null && !note.isBlank()) {
            this.accountantNote = note;
        }
    }

    /** 税理士がまだ見ていないか。作業台の並びに使う。 */
    public boolean isAwaitingAccountant() {
        return accountantCheckedAt == null && !deleted;
    }

    public List<PurchaseLine> getLines() {
        return lines;
    }
}
