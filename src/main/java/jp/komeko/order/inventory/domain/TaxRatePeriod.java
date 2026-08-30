package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 「いつからいつまで、この区分は何 % か」を 1 行で表す税率マスタ。
 *
 * <p><b>なぜコードに書かず、テーブルに置くのか</b><br>
 * 消費税率は変わります。しかも<b>先に決まって、あとから施行される</b>。
 * 2026-08-05 には「飲食料品を 2027-04-01 から 2 年間 1% にする」方針が閣議決定されました
 * （法案は未成立）。コードに {@code if (食材) return 8;} と書いていると、
 * 改正のたびにプログラムを直して再デプロイすることになります。
 *
 * <p>行として持てば、改定は<b>行を 1 本足すだけ</b>。しかも施行日を未来にして
 * 先に登録しておけるので、当日に誰かが設定を変え忘れる事故が起きません。
 * 過去の行を消さずに残すので、去年の伝票を計算し直しても去年の率で合います。
 *
 * <p><b>区分を分けてある理由</b><br>
 * 「標準」と「軽減」の 2 値では足りません。飲食料品が 1% になっても新聞は 8% のままなので、
 * 施行後は 10% / 8% / 1% の 3 つが同時に存在します。
 * 区分を文字列で持っておけば、4 つ目が現れても行を足すだけで済みます。
 *
 * @see jp.komeko.order.inventory.service.TaxRuleService
 */
@Entity
@Table(name = "tax_rate_period")
public class TaxRatePeriod {

    /** 区分: 標準税率。 */
    public static final String CLASS_STANDARD = "標準";
    /** 区分: 軽減税率（飲食料品）。 */
    public static final String CLASS_REDUCED_FOOD = "軽減_飲食料品";
    /** 区分: 軽減税率（定期購読の新聞）。 */
    public static final String CLASS_REDUCED_NEWSPAPER = "軽減_新聞";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 税率の区分。{@link #CLASS_STANDARD} などの定数を使う。 */
    @Column(name = "rate_class", nullable = false, length = 30)
    private String rateClass;

    /**
     * 税率（%）。
     *
     * <p>{@code int} なのは、日本の消費税率が 3 / 5 / 8 / 10 と整数でしか設定されたことがなく、
     * 検討中の 1% も整数だからです。既存の {@code TaxCalculator} も {@code int} を取ります。
     * 小数の税率が現れたら、そのときは両方あわせて直すことになります。
     */
    @Column(name = "rate_percent", nullable = false)
    private int ratePercent;

    /** 適用開始日（この日を含む）。 */
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** 適用終了日（この日を含む）。null は「今のところ終わりが決まっていない」。 */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /** 何の改正による行かのメモ（画面には出さないが、後から読む人のために残す）。 */
    @Column(length = 200)
    private String note;

    protected TaxRatePeriod() {
        // JPA 用
    }

    public TaxRatePeriod(String rateClass, int ratePercent, LocalDate validFrom, LocalDate validTo, String note) {
        this.rateClass = rateClass;
        this.ratePercent = ratePercent;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.note = note;
    }

    /** 指定日にこの行が有効か。 */
    public boolean coversOn(LocalDate date) {
        if (date == null || validFrom == null) {
            return false;
        }
        if (date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !date.isAfter(validTo);
    }

    public Long getId() {
        return id;
    }

    public String getRateClass() {
        return rateClass;
    }

    public int getRatePercent() {
        return ratePercent;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public String getNote() {
        return note;
    }
}
