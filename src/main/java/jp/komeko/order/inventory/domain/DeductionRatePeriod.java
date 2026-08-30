package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 免税事業者などからの仕入れについて、消費税を何 % 引いてよいか（経過措置の控除率）。
 *
 * <p><b>何の話か</b><br>
 * インボイス制度では、登録していない事業者から仕入れた分の消費税は原則として引けません。
 * ただし急に全額引けなくなると影響が大きいので、
 * 「しばらくは何割か引いてよい」という経過措置が置かれています。
 * その割合は<b>数年ごとに段階的に下がって、最後はゼロになります</b>。
 *
 * <p><b>2026-08 時点で確定しているスケジュール</b>（令和 8 年度税制改正後）
 * <pre>
 *   2023-10-01 〜 2026-09-30 … 80%
 *   2026-10-01 〜 2028-09-30 … 70%   ← 改正でここが 50% から 70% に緩和された
 *   2028-10-01 〜 2030-09-30 … 50%
 *   2030-10-01 〜 2031-09-30 … 30%
 *   2031-10-01 〜            …  0%（控除できない）
 * </pre>
 *
 * <p>2031 年の終わりまで決まっているので、<b>初期データとして全部を先に登録しておきます</b>。
 * そうすれば、切り替わりの日に誰も何もしなくても正しい率が使われます。
 *
 * <p><b>帳簿への記載義務</b><br>
 * 経過措置を使った仕入れは、帳簿に「80% 控除対象」のように<b>書かなければなりません</b>。
 * 仕訳を書き出すときに摘要へ自動で付ける必要があるので、
 * 適用した率は {@code Purchase} 側にも写して残します。
 *
 * @see jp.komeko.order.inventory.service.TaxRuleService
 */
@Entity
@Table(name = "deduction_rate_period")
public class DeductionRatePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 控除率（%）。80 / 70 / 50 / 30 / 0。 */
    @Column(name = "rate_percent", nullable = false)
    private int ratePercent;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** null は「今のところ終わりが決まっていない」。 */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(length = 200)
    private String note;

    protected DeductionRatePeriod() {
        // JPA 用
    }

    public DeductionRatePeriod(int ratePercent, LocalDate validFrom, LocalDate validTo, String note) {
        this.ratePercent = ratePercent;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.note = note;
    }

    public boolean coversOn(LocalDate date) {
        if (date == null || validFrom == null) {
            return false;
        }
        if (date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !date.isAfter(validTo);
    }

    /** 帳簿の摘要に付ける文言（例:「80%控除対象」）。控除できない場合は「控除対象外」。 */
    public String bookkeepingNote() {
        return ratePercent > 0 ? ratePercent + "%控除対象" : "控除対象外";
    }

    public Long getId() {
        return id;
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

    public String getNote() {
        return note;
    }
}
