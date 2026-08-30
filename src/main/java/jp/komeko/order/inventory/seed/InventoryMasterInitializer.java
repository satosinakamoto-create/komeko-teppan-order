package jp.komeko.order.inventory.seed;

import jp.komeko.order.inventory.domain.DeductionRatePeriod;
import jp.komeko.order.inventory.domain.TaxRatePeriod;
import jp.komeko.order.inventory.repository.DeductionRatePeriodRepository;
import jp.komeko.order.inventory.repository.TaxRatePeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 税率・控除率マスタの初期データを入れる。
 *
 * <p><b>これはサンプルデータではありません。</b>
 * 消費税の計算に必ず要る値なので、{@code app.seed-on-startup} の設定に関係なく、
 * テーブルが空のときだけ投入します。すでに行があるときは何もしません
 * （運用中に手で直した内容を上書きしないため）。
 *
 * <p><b>控除率は 2031 年ぶんまで先に入れる</b><br>
 * 経過措置の段階的な引き下げは 2031-09-30 の終了まで法律で決まっています。
 * 決まっているものを先に全部入れておけば、切り替わりの日に
 * 誰も何もしなくても正しい率が使われます。
 * 「その日になったら誰かが直す」は、個人で回す店ではまず守られません。
 */
@Component
@Order(100)   // 既存の DataSeeder より後で構わない（互いに独立している）
public class InventoryMasterInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryMasterInitializer.class);

    private final TaxRatePeriodRepository taxRates;
    private final DeductionRatePeriodRepository deductionRates;

    public InventoryMasterInitializer(TaxRatePeriodRepository taxRates,
                                      DeductionRatePeriodRepository deductionRates) {
        this.taxRates = taxRates;
        this.deductionRates = deductionRates;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedTaxRates();
        seedDeductionRates();
    }

    private void seedTaxRates() {
        if (taxRates.count() > 0) {
            return;
        }
        LocalDate since2019 = LocalDate.of(2019, 10, 1);
        taxRates.saveAll(List.of(
                new TaxRatePeriod(TaxRatePeriod.CLASS_STANDARD, 10, since2019, null,
                        "2019-10-01 の税率引き上げ"),
                new TaxRatePeriod(TaxRatePeriod.CLASS_REDUCED_FOOD, 8, since2019, null,
                        "軽減税率（酒類・外食を除く飲食料品）"),
                new TaxRatePeriod(TaxRatePeriod.CLASS_REDUCED_NEWSPAPER, 8, since2019, null,
                        "軽減税率（週2回以上発行の定期購読新聞）")
        ));

        // ── 飲食料品 1% について ──
        //
        // 2026-08-05 の閣議決定で「2027-04-01 から 2 年間、軽減税率対象の
        // 飲食料品を 1% にする」方針が示されました。ただし<b>法案は未成立</b>です。
        // 成立していない制度を本番データに入れると、あとで取り消す作業が要ります。
        //
        // 成立したら、次の 2 行を足すだけで対応できます（コードの修正は不要）:
        //   1. 上の「軽減_飲食料品 8%」の valid_to を 2027-03-31 にする
        //   2. 軽減_飲食料品 1% / 2027-04-01 〜 2029-03-31 の行を足す
        //   3. 軽減_飲食料品 8% / 2029-04-01 〜 の行を足す（時限措置なので戻る）
        //
        // 忘れても、期限が近づけば TaxRuleService のマスタ終端警告が知らせます。

        log.info("税率マスタの初期データを投入しました（標準 10% / 軽減 8%）");
    }

    private void seedDeductionRates() {
        if (deductionRates.count() > 0) {
            return;
        }
        // 免税事業者などからの仕入れに係る経過措置。
        // 令和8年度税制改正で「2026-10-01 から 50%」が「70%」に緩和され、
        // 終了も 2029 年から 2031 年へ延びました。ここは改正後の日程です。
        deductionRates.saveAll(List.of(
                new DeductionRatePeriod(80, LocalDate.of(2023, 10, 1), LocalDate.of(2026, 9, 30),
                        "インボイス制度開始時の経過措置"),
                new DeductionRatePeriod(70, LocalDate.of(2026, 10, 1), LocalDate.of(2028, 9, 30),
                        "令和8年度税制改正（当初の 50% から緩和）"),
                new DeductionRatePeriod(50, LocalDate.of(2028, 10, 1), LocalDate.of(2030, 9, 30),
                        "令和8年度税制改正"),
                new DeductionRatePeriod(30, LocalDate.of(2030, 10, 1), LocalDate.of(2031, 9, 30),
                        "令和8年度税制改正"),
                new DeductionRatePeriod(0, LocalDate.of(2031, 10, 1), null,
                        "経過措置の終了。以後は控除できない")
        ));
        log.info("控除率マスタの初期データを投入しました（2031 年の経過措置終了ぶんまで）");
    }
}
