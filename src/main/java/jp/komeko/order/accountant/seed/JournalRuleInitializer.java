package jp.komeko.order.accountant.seed;

import jp.komeko.order.accountant.domain.JournalRule;
import jp.komeko.order.accountant.repository.JournalRuleRepository;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仕訳の対応表に、たたき台を入れる。
 *
 * <p><b>これは「正解」ではなく「出発点」です。</b>
 * 勘定科目も税区分も、最終的には顧問税理士の事務所の科目体系に合わせます。
 * とくに<b>税区分の文字列は事務所ごとに違い</b>、弥生は登録名と 1 文字でも
 * 違うと取り込みを弾きます。だから画面から直せるようにしてあり、
 * ここではよくある名前を置いているだけです。
 *
 * <p>空のまま渡すと「まず 20 行埋めてください」から始まってしまい、
 * 税理士が最初の 5 分で離脱します。埋まった状態で見せて、
 * 「違うところだけ直してください」にするのが狙いです。
 */
@Component
public class JournalRuleInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JournalRuleInitializer.class);

    /** 経過措置の控除率。制度で決まっている段階（80→70→50→30→0）と全額控除。 */
    private static final int[] DEDUCTION_RATES = {100, 80, 70, 50, 30, 0};

    private final JournalRuleRepository rules;

    public JournalRuleInitializer(JournalRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = rules.count();
        if (existing > 0) {
            // ★ 何もしないときも記録を残す。
            //   黙って戻ると、あとで「対応表が空だ」となったときに
            //   「動かなかった」のか「動いて何もしなかった」のか区別できない。
            log.info("仕訳の対応表はすでに {} 行あるため、初期値は入れませんでした", existing);
            return;   // すでに人が直しているかもしれないので触らない
        }

        int created = 0;
        for (PurchaseCategory category : PurchaseCategory.values()) {
            for (int taxRate : new int[]{10, 8}) {
                for (int deduction : DEDUCTION_RATES) {
                    rules.save(new JournalRule(category, taxRate, deduction,
                            accountFor(category), taxClassFor(taxRate, deduction),
                            "初期値。事務所の科目体系に合わせて直してください"));
                    created++;
                }
            }
        }
        log.info("仕訳の対応表に初期値を {} 行入れました（画面から直せます）", created);
    }

    /**
     * 費目から勘定科目のたたき台を決める。
     *
     * <p>飲食店でよく使われる科目に寄せています。
     * 「仕入高」を使うか「材料費」を使うかは事務所の流儀なので、
     * 違えば画面で直してもらいます。
     */
    private String accountFor(PurchaseCategory category) {
        return switch (category) {
            case FOOD -> "仕入高";
            case DRINK -> "仕入高";
            case SUPPLIES -> "消耗品費";
            case UTILITIES -> "水道光熱費";
            case OTHER -> "雑費";
        };
    }

    /**
     * 税率と控除率から税区分名のたたき台を決める。
     *
     * <p>弥生でよく見る書き方に寄せています。
     * <b>事務所の登録名と完全一致していないと取り込めない</b>ので、
     * 実際に使う前に必ず税理士に確認してもらう前提です。
     */
    private String taxClassFor(int taxRatePercent, int deductionRatePercent) {
        String base = taxRatePercent == 8 ? "課対仕入8%(軽)" : "課対仕入" + taxRatePercent + "%";
        if (deductionRatePercent >= 100) {
            return base;
        }
        if (deductionRatePercent <= 0) {
            return "対象外";
        }
        // 経過措置は「何%控除か」まで区分が分かれる
        return base + "･" + deductionRatePercent + "%控除";
    }
}
