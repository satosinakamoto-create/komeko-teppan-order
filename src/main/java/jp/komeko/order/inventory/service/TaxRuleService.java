package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.config.InventoryProperties;
import jp.komeko.order.inventory.domain.DeductionRatePeriod;
import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.domain.TaxRatePeriod;
import jp.komeko.order.inventory.repository.DeductionRatePeriodRepository;
import jp.komeko.order.inventory.repository.TaxRatePeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 「その日、その区分の税率は何 % か」「その仕入れは何 % 引けるか」を答える係。
 *
 * <p><b>このクラスがあることの意味</b><br>
 * 税率をコードに書かず、日付でマスタを引く。それだけのことですが、
 * これがあると税制改正のたびのプログラム修正が不要になります。
 * 逆に言えば、<b>税率に関する判断はすべてここを通す</b>のがルールです。
 * ほかの場所に {@code if (食材) 8} と書いた瞬間、この仕組みは意味を失います。
 *
 * <p><b>データ駆動の弱点と、その保険</b><br>
 * 行として持つということは、<b>誰かが行を足さないと止まる</b>ということです。
 * 個人で運用するとき、いちばん現実的な事故は技術的な失敗ではなく
 * 「制度が変わったことに気づかない」ことです。
 * だから {@link #masterWarnings} で、期限が近い行と、
 * その日に有効な行がないことをシステム側から知らせます。
 */
@Service
public class TaxRuleService {

    /** マスタに行が見つからないときに使う税率（%）。標準税率。 */
    private static final int FALLBACK_TAX_RATE = 10;

    /** マスタに行が見つからないときに使う控除率（%）。安全側に倒して「引けない」。 */
    private static final int FALLBACK_DEDUCTION_RATE = 0;

    private final TaxRatePeriodRepository taxRates;
    private final DeductionRatePeriodRepository deductionRates;
    private final InventoryProperties properties;

    public TaxRuleService(TaxRatePeriodRepository taxRates,
                          DeductionRatePeriodRepository deductionRates,
                          InventoryProperties properties) {
        this.taxRates = taxRates;
        this.deductionRates = deductionRates;
        this.properties = properties;
    }

    // ========================================================================
    //  税率
    // ========================================================================

    /**
     * 指定日に有効な税率（%）を返す。見つからなければ標準税率で代替する。
     *
     * <p>見つからないのは「マスタの更新漏れ」なので、
     * 黙って代替値を使うだけでなく {@link #masterWarnings} 側で警告を出します。
     *
     * @param rateClass {@link TaxRatePeriod#CLASS_STANDARD} などの区分
     * @param date      取引日
     */
    @Transactional(readOnly = true)
    public int taxRateOn(String rateClass, LocalDate date) {
        List<TaxRatePeriod> found = taxRates.findActive(rateClass, date);
        if (found.isEmpty()) {
            return FALLBACK_TAX_RATE;
        }
        // findActive は valid_from の新しい順。期間が重なっていたら新しいほうを採る。
        return found.get(0).getRatePercent();
    }

    /** 指定日に有効な税率をすべて（区分つき）。確認画面の選択肢に使う。 */
    @Transactional(readOnly = true)
    public List<TaxRatePeriod> activeRatesOn(LocalDate date) {
        List<TaxRatePeriod> result = new ArrayList<>();
        for (String rateClass : List.of(TaxRatePeriod.CLASS_STANDARD,
                TaxRatePeriod.CLASS_REDUCED_FOOD,
                TaxRatePeriod.CLASS_REDUCED_NEWSPAPER)) {
            List<TaxRatePeriod> found = taxRates.findActive(rateClass, date);
            if (!found.isEmpty()) {
                result.add(found.get(0));
            }
        }
        return result;
    }

    // ========================================================================
    //  控除率（経過措置）
    // ========================================================================

    /**
     * その仕入れで消費税を何 % 引けるかを返す。
     *
     * <p>登録番号のある証憑（適格請求書・適格簡易請求書）と、
     * 少額特例が使える仕入れは全額（100%）。
     * それ以外は、その日の経過措置の率を引きます。
     *
     * @param evidenceType 証憑の区分
     * @param date         取引日
     */
    @Transactional(readOnly = true)
    public int deductionRateOn(EvidenceType evidenceType, LocalDate date) {
        if (evidenceType != null && evidenceType.isFullyDeductible()) {
            return 100;
        }
        List<DeductionRatePeriod> found = deductionRates.findActive(date);
        if (found.isEmpty()) {
            return FALLBACK_DEDUCTION_RATE;
        }
        return found.get(0).getRatePercent();
    }

    /**
     * 帳簿の摘要に付ける文言（例:「80%控除対象」）。
     *
     * <p>経過措置を使った仕入れは、帳簿にその旨を書くことが<b>義務</b>です。
     * 全額引ける仕入れには何も付けません（付けると逆に嘘になる）。
     */
    public String bookkeepingNoteFor(int deductionRatePercent) {
        if (deductionRatePercent >= 100) {
            return null;
        }
        return deductionRatePercent > 0 ? deductionRatePercent + "%控除対象" : "控除対象外";
    }

    // ========================================================================
    //  マスタ終端警告
    // ========================================================================

    /**
     * 税率・控除率マスタについて、人が手を打つべきことを文章で返す。
     *
     * <p>返すのは 2 種類:
     * <ul>
     *   <li><b>もうすぐ終わる</b> … 期限が近い行がある。次の行が登録されているか確かめる合図</li>
     *   <li><b>今日の分がない</b> … その日に有効な行がない。代替値で動いてしまっているので至急直す</li>
     * </ul>
     *
     * @param asOf 基準日（ふつうは今日）
     * @return 警告文のリスト。問題がなければ空
     */
    @Transactional(readOnly = true)
    public List<String> masterWarnings(LocalDate asOf) {
        List<String> warnings = new ArrayList<>();
        LocalDate horizon = asOf.plusDays(properties.rateWarningDays());

        // ── その日に有効な行があるか ──
        for (String rateClass : List.of(TaxRatePeriod.CLASS_STANDARD, TaxRatePeriod.CLASS_REDUCED_FOOD)) {
            if (taxRates.findActive(rateClass, asOf).isEmpty()) {
                warnings.add("税率マスタに「" + rateClass + "」の " + asOf + " 時点で有効な行がありません。"
                        + "いまは標準税率 " + FALLBACK_TAX_RATE + "% で代替しています。至急ご確認ください。");
            }
        }
        if (deductionRates.findActive(asOf).isEmpty()) {
            warnings.add("控除率マスタに " + asOf + " 時点で有効な行がありません。"
                    + "いまは控除なし（0%）で計算しています。至急ご確認ください。");
        }

        // ── もうすぐ終わる行があるか ──
        for (TaxRatePeriod period : taxRates.findExpiringFrom(asOf)) {
            if (period.getValidTo().isAfter(horizon)) {
                break;   // 期限の近い順なので、これ以降は範囲外
            }
            if (taxRates.findActive(period.getRateClass(), period.getValidTo().plusDays(1)).isEmpty()) {
                warnings.add("税率マスタ「" + period.getRateClass() + " " + period.getRatePercent() + "%」は "
                        + period.getValidTo() + " で終わりますが、その翌日から使う行がありません。"
                        + "税制改正を確認して行を足してください。");
            }
        }
        for (DeductionRatePeriod period : deductionRates.findExpiringFrom(asOf)) {
            if (period.getValidTo().isAfter(horizon)) {
                break;
            }
            if (deductionRates.findActive(period.getValidTo().plusDays(1)).isEmpty()) {
                warnings.add("控除率マスタ「" + period.getRatePercent() + "%」は "
                        + period.getValidTo() + " で終わりますが、その翌日から使う行がありません。"
                        + "税制改正を確認して行を足してください。");
            }
        }
        return warnings;
    }
}
