package jp.komeko.order.accountant.service;

import jp.komeko.order.inventory.domain.Purchase;
import jp.komeko.order.inventory.domain.PurchaseLine;
import jp.komeko.order.inventory.repository.PurchaseRepository;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.inventory.service.PurchaseSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 税理士の月次作業を支える集計。
 *
 * <p><b>ここで新しい事実は 1 つも作りません。</b>
 * すでに店が記録したもの（仕入れ・明細・売上）を、
 * <b>申告書に写せる形</b>に組み替えるだけです。
 *
 * <p>税理士が月に一度やることは決まっています。
 * 資料を受け取り、仕訳にし、証憑と突き合わせ、おかしなところを店に聞き、
 * 消費税の集計を出す。その順番に画面が並ぶよう作っています。
 */
@Service
public class AccountantService {

    private static final Logger log = LoggerFactory.getLogger(AccountantService.class);

    private final PurchaseRepository purchases;
    private final PurchaseService purchaseService;
    private final Clock clock;

    public AccountantService(PurchaseRepository purchases,
                             PurchaseService purchaseService,
                             Clock clock) {
        this.purchases = purchases;
        this.purchaseService = purchaseService;
        this.clock = clock;
    }

    /** その月のまとめ（売上・仕入れ・原価率）。在庫モジュールの集計をそのまま使う。 */
    @Transactional(readOnly = true)
    public PurchaseSummary summarize(YearMonth month) {
        return purchaseService.summarize(month);
    }

    /**
     * 消費税の集計表。<b>申告書に写す数字そのもの</b>。
     *
     * <p>「税率 × 控除区分」で束ねます。税率だけでは足りません。
     * 同じ 8% でも、登録番号のある仕入れは全額控除、
     * 無い仕入れは経過措置（いまは 80%、2026-10 から 70%）で、
     * <b>控除できる税額が違う</b>からです。
     *
     * <p>控除税額は「税額 × 控除率 ÷ 100」を<b>区分ごとに 1 回だけ</b>丸めます。
     * レシートごとに丸めると、枚数が多いほどずれが積み上がります。
     */
    @Transactional(readOnly = true)
    public List<TaxRow> taxBreakdown(YearMonth month) {
        Map<String, TaxRow> rows = new LinkedHashMap<>();

        for (Purchase purchase : purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth())) {
            if (purchase.isDeleted()) {
                continue;   // 帳簿には載せない
            }
            int deduction = purchase.getDeductionRatePercent();
            for (PurchaseLine line : purchase.getLines()) {
                String key = line.getTaxRatePercent() + "-" + deduction;
                TaxRow row = rows.computeIfAbsent(key,
                        k -> new TaxRow(line.getTaxRatePercent(), deduction));
                row.grossAmount += line.getAmount();
                row.netAmount += line.netAmount();
                row.taxAmount += line.effectiveTaxAmount();
            }
        }

        List<TaxRow> result = new ArrayList<>(rows.values());
        // 税率の高い順 → 控除率の高い順。申告書の並びに近い
        result.sort((a, b) -> a.taxRatePercent != b.taxRatePercent
                ? Integer.compare(b.taxRatePercent, a.taxRatePercent)
                : Integer.compare(b.deductionRatePercent, a.deductionRatePercent));
        return result;
    }

    /**
     * 税理士が見るべき仕入れ（作業台）。
     *
     * <p>未確認のものを先に、そのあと確認済みを日付の新しい順で返します。
     * 「どれをまだ見ていないか」を人の記憶に頼らせないためです。
     */
    @Transactional(readOnly = true)
    public List<Purchase> workQueue(YearMonth month) {
        List<Purchase> found = new ArrayList<>(purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth()));
        found.sort((a, b) -> {
            if (a.isAwaitingAccountant() != b.isAwaitingAccountant()) {
                return a.isAwaitingAccountant() ? -1 : 1;
            }
            return b.getPurchasedOn().compareTo(a.getPurchasedOn());
        });
        return found;
    }

    /**
     * 手当てが要る仕入れ（要確認キュー）。
     *
     * <p>在庫側の {@code needingAttention} と目的が違います。
     * あちらは店が直すもの、こちらは<b>税理士が判断するもの</b>。
     * 登録番号が無い（控除が減る）、紙の保管が要る、
     * レシートの合計と明細が合わない、といった「聞かないと決められない」ものを集めます。
     */
    @Transactional(readOnly = true)
    public List<Attention> attentions(YearMonth month) {
        List<Attention> list = new ArrayList<>();
        for (Purchase purchase : purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth())) {
            if (purchase.isDeleted()) {
                continue;
            }
            if (purchase.getRegNumber() == null) {
                list.add(new Attention(purchase, "登録番号なし",
                        "控除率 " + purchase.getDeductionRatePercent() + "% で計上されています"));
            }
            if (purchase.isPaperRetentionRequired()) {
                list.add(new Attention(purchase, "紙の原本が必要",
                        "入力期限を過ぎているため、原本を破棄できません"));
            }
            if (purchase.getEquivalenceCheckedAt() == null) {
                list.add(new Attention(purchase, "同等確認が未了",
                        "店側で紙と画像の突き合わせがまだです"));
            }
            int mismatch = purchase.lineTotalMismatch();
            if (mismatch != 0) {
                list.add(new Attention(purchase, "合計と明細のずれ",
                        "差額 " + mismatch + " 円。値引き行や読み取り漏れの可能性"));
            }
        }
        return list;
    }

    /** 削除された記録（訂正の透明性）。電子帳簿保存法が履歴を残すことを求めている。 */
    @Transactional(readOnly = true)
    public List<Purchase> deleted(YearMonth month) {
        List<Purchase> list = new ArrayList<>();
        for (Purchase purchase : purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth())) {
            if (purchase.isDeleted()) {
                list.add(purchase);
            }
        }
        return list;
    }

    /** 未確認の件数（画面の見出しに出す）。 */
    @Transactional(readOnly = true)
    public long awaitingCount(YearMonth month) {
        return workQueue(month).stream().filter(Purchase::isAwaitingAccountant).count();
    }

    /** 1 件を明細つきで読む。 */
    @Transactional(readOnly = true)
    public Purchase find(Long id) {
        return purchases.findByIdWithLines(id).orElse(null);
    }

    /**
     * 「税理士として確認した」を記録する。
     *
     * <p><b>税理士が書けるのはここだけです。</b>店の数字は直せません。
     * 外部の人が帳簿を書き換えられると、責任の所在が曖昧になるからです。
     * 直すべき点は申し送り（note）で店に伝え、店が直します。
     */
    @Transactional
    public void markChecked(Long id, Long staffId, String note) {
        purchases.findById(id).ifPresent(purchase -> {
            purchase.markAccountantChecked(LocalDateTime.now(clock), staffId, note);
            log.info("税理士が確認しました: id={} 店={}", id, purchase.getStoreName());
        });
    }

    /** 今日の月（テストから差し替えられるよう Clock 経由）。 */
    public YearMonth currentMonth() {
        return YearMonth.from(java.time.LocalDate.now(clock));
    }

    /**
     * 月を指定せずに開いたときに見せる月。<b>前月</b>。
     *
     * <p>当月ではありません。税理士が扱うのは締まった月で、
     * 当月はまだ仕入れが積み上がっている途中だからです。
     *
     * <p>当月を既定にしていたときは、月初に開くと数字がほとんど無く、
     * <b>画面が壊れているのか、まだ何も無いだけなのか区別できません</b>でした。
     * 前月なら 1 か月ぶんが揃っているので、開いた瞬間に仕事が始められます。
     * 当月を見たいときは「翌月 →」で移動できます。
     */
    public YearMonth defaultMonth() {
        return currentMonth().minusMonths(1);
    }

    /**
     * 消費税の集計 1 行（税率 × 控除区分）。
     *
     * @param taxRatePercent       税率（%）
     * @param deductionRatePercent 控除率（%）。100=全額、80/70=経過措置、0=控除なし
     */
    public static final class TaxRow {
        private final int taxRatePercent;
        private final int deductionRatePercent;
        private int grossAmount;
        private int netAmount;
        private int taxAmount;

        TaxRow(int taxRatePercent, int deductionRatePercent) {
            this.taxRatePercent = taxRatePercent;
            this.deductionRatePercent = deductionRatePercent;
        }

        public int getTaxRatePercent() {
            return taxRatePercent;
        }

        public int getDeductionRatePercent() {
            return deductionRatePercent;
        }

        /** 税込の課税仕入額。 */
        public int getGrossAmount() {
            return grossAmount;
        }

        /** 税抜（本体）。 */
        public int getNetAmount() {
            return netAmount;
        }

        /** その区分に含まれる消費税額。 */
        public int getTaxAmount() {
            return taxAmount;
        }

        /**
         * 実際に控除できる税額。
         *
         * <p>区分ごとに 1 回だけ丸めます。レシートごとに丸めて足すと、
         * 枚数が多いほどずれが積み上がるためです（既存の「合計してから 1 回」と同じ）。
         */
        public int getDeductibleTax() {
            return (int) ((long) taxAmount * deductionRatePercent / 100);
        }

        /** 控除区分の表示名。 */
        public String getDeductionLabel() {
            return switch (deductionRatePercent) {
                case 100 -> "全額控除";
                case 0 -> "控除なし";
                default -> "経過措置 " + deductionRatePercent + "%";
            };
        }
    }

    /**
     * 税理士が判断すべき事柄。
     *
     * @param purchase 対象の仕入れ
     * @param label    何が起きているか（短く）
     * @param detail   なぜ手当てが要るか
     */
    public record Attention(Purchase purchase, String label, String detail) {
    }
}
