package jp.komeko.order.accountant.domain;

import jakarta.persistence.*;
import jp.komeko.order.inventory.domain.PurchaseCategory;

/**
 * 店の言葉を、会計の言葉に翻訳する 1 行。
 *
 * <p>店は「食材を 8% で買った、インボイスは無かった」と記録します。
 * 会計ソフトが欲しいのは「仕入高 / 課対仕入(軽)8%・80%控除」という言葉です。
 * その対応を持つのがこの表です。
 *
 * <p><b>なぜ 3 つのキーが要るのか</b><br>
 * 「カテゴリ×税率」の 2 つでは足りません。同じ食材の 8% でも、
 * 登録番号のある仕入れは<b>全額控除</b>、無い仕入れは<b>経過措置 80%</b>で、
 * 会計ソフト上はまったく別の税区分になります。控除率まで見て初めて決まります。
 *
 * <p><b>なぜコードに書かないのか</b><br>
 * 税区分の文字列は会計ソフトごとに違ううえ、弥生では
 * <b>その事務所に登録されている名称と完全一致</b>していないと取り込みが弾かれます。
 * 「課対仕入8%(軽)」なのか「課対仕入（軽）8%」なのかは事務所の設定次第で、
 * こちらから決められません。だから画面から直せる行として持ちます。
 * ここを間違えると、CSV が丸ごと取り込めません。
 */
@Entity
@Table(name = "journal_rule", indexes = {
        @Index(name = "idx_journal_rule_lookup",
                columnList = "category, tax_rate_percent, deduction_rate_percent")
})
public class JournalRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 店の費目（食材・飲料・消耗品・光熱費・その他）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private PurchaseCategory category;

    /** その仕入れの税率（%）。8 と 10 で勘定も税区分も変わる。 */
    @Column(name = "tax_rate_percent", nullable = false)
    private int taxRatePercent;

    /** 適用された控除率（%）。100=全額、80/70/50/30=経過措置、0=控除なし。 */
    @Column(name = "deduction_rate_percent", nullable = false)
    private int deductionRatePercent;

    /** 会計ソフト側の勘定科目名（「仕入高」「消耗品費」など）。 */
    @Column(name = "account_name", nullable = false, length = 24)
    private String accountName;

    /**
     * 会計ソフト側の税区分名。
     *
     * <p><b>事務所の登録名と 1 文字も違ってはいけません。</b>
     * 弥生はここが一致しない行を取り込みません。
     */
    @Column(name = "tax_class_name", nullable = false, length = 32)
    private String taxClassName;

    @Column(length = 200)
    private String note;

    protected JournalRule() {
        // JPA 用
    }

    public JournalRule(PurchaseCategory category, int taxRatePercent, int deductionRatePercent,
                       String accountName, String taxClassName, String note) {
        this.category = category;
        this.taxRatePercent = taxRatePercent;
        this.deductionRatePercent = deductionRatePercent;
        this.accountName = accountName;
        this.taxClassName = taxClassName;
        this.note = note;
    }

    /** 画面で「どの組み合わせの行か」を 1 行で見せる。 */
    public String describe() {
        String deduction = switch (deductionRatePercent) {
            case 100 -> "全額控除";
            case 0 -> "控除なし";
            default -> deductionRatePercent + "%控除";
        };
        return category.getLabel() + " / " + taxRatePercent + "% / " + deduction;
    }

    // ── getter / setter ──

    public Long getId() {
        return id;
    }

    public PurchaseCategory getCategory() {
        return category;
    }

    public int getTaxRatePercent() {
        return taxRatePercent;
    }

    public int getDeductionRatePercent() {
        return deductionRatePercent;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getTaxClassName() {
        return taxClassName;
    }

    public void setTaxClassName(String taxClassName) {
        this.taxClassName = taxClassName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
