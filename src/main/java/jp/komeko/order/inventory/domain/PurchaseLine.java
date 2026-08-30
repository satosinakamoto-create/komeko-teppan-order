package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;
import jp.komeko.order.domain.TaxCalculator;

import java.math.BigDecimal;

/**
 * レシートの明細 1 行。
 *
 * <p><b>税率とカテゴリを「行」に持つ</b><br>
 * スーパーのレシートには、キャベツ（食材・軽減 8%）と洗剤（消耗品・標準 10%）が
 * 並んで印字されます。レシート 1 枚に 1 つの税率、という持ち方では表現できません。
 *
 * <p><b>保存するのは「レシートに印字されていた事実」</b><br>
 * 税率をマスタから推定して書き込んではいけません。施行日をまたぐ返品や
 * 月をまたぐ請求など、取引日とマスタだけでは再現できない組み合わせが実在します。
 * 印字されたとおりに写し、税抜額は表示のたびに逆算します。
 *
 * <p><b>品名は生のまま残す</b><br>
 * {@link #itemText} はレシートの文字そのもの（「ｷｬﾍﾞﾂ 1/2」など）です。
 * これを整形して捨ててしまうと、あとから食材マスタに名寄せするときの材料がなくなります。
 * 表示用にきれいにするのは画面の仕事で、記録は生に近いほうが強い。
 */
@Entity
@Table(name = "purchase_line")
public class PurchaseLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    /** レシート上の並び順（1 始まり）。 */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /** レシートに印字されていた品名。整形せずそのまま。 */
    @Column(name = "item_text", nullable = false, length = 120)
    private String itemText;

    /** 個数。0.5 パックがあり得るので小数を許す。 */
    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    /** この行の合計金額（税込・円）。 */
    @Column(nullable = false)
    private int amount;

    /** この行の税率（%）。レシートに印字されていた値。 */
    @Column(name = "tax_rate_percent", nullable = false)
    private int taxRatePercent;

    /**
     * この行の消費税額（円）。
     *
     * <p><b>null があり得ます。</b>適格簡易請求書は「税率ごとの消費税額」か
     * 「適用税率」の<b>どちらか一方</b>を書けばよいので、税額の印字がないレシートは合法です。
     * その場合は {@link #netAmount()} のように税込額から逆算します。
     */
    @Column(name = "tax_amount")
    private Integer taxAmount;

    /** 経営上の費目。原価率の分子に入るのは {@link PurchaseCategory#FOOD} だけ。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseCategory category = PurchaseCategory.FOOD;

    protected PurchaseLine() {
        // JPA 用
    }

    public PurchaseLine(int lineNo, String itemText, BigDecimal quantity,
                        int amount, int taxRatePercent, Integer taxAmount, PurchaseCategory category) {
        this.lineNo = lineNo;
        this.itemText = itemText;
        this.quantity = quantity;
        this.amount = amount;
        this.taxRatePercent = taxRatePercent;
        this.taxAmount = taxAmount;
        this.category = category != null ? category : PurchaseCategory.FOOD;
    }

    /**
     * 税抜（本体）金額。
     *
     * <p>レシートに税額の印字があればそれを引き、なければ税率から逆算します。
     * 逆算は既存の {@link TaxCalculator} に任せます（自前で計算しない、が既存の規約）。
     */
    public int netAmount() {
        if (taxAmount != null) {
            return amount - taxAmount;
        }
        return TaxCalculator.netAmount(amount, taxRatePercent);
    }

    /** 消費税額。印字があればその値、なければ逆算値。 */
    public int effectiveTaxAmount() {
        if (taxAmount != null) {
            return taxAmount;
        }
        return TaxCalculator.includedTax(amount, taxRatePercent);
    }

    /** 軽減税率（標準税率より低い率）が適用されている行か。表示の「※」印に使う。 */
    public boolean isReducedRate() {
        return taxRatePercent < 10;
    }

    // ── ここから下は getter / setter（Lombok は使わない規約） ──

    public Long getId() {
        return id;
    }

    public Purchase getPurchase() {
        return purchase;
    }

    /** {@link Purchase#addLine} から呼ばれる。双方向の関連を張るため。 */
    void setPurchase(Purchase purchase) {
        this.purchase = purchase;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getItemText() {
        return itemText;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public int getAmount() {
        return amount;
    }

    public int getTaxRatePercent() {
        return taxRatePercent;
    }

    public Integer getTaxAmount() {
        return taxAmount;
    }

    public PurchaseCategory getCategory() {
        return category;
    }
}
