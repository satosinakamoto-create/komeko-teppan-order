package jp.komeko.order.accountant.service;

import jp.komeko.order.accountant.domain.JournalRule;
import jp.komeko.order.accountant.repository.JournalRuleRepository;
import jp.komeko.order.inventory.domain.Purchase;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import jp.komeko.order.inventory.domain.PurchaseLine;
import jp.komeko.order.inventory.repository.PurchaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 仕入れの記録を、会計ソフトが取り込める仕訳 CSV に変換する。
 *
 * <p><b>形式は弥生の 25 項目固定。</b>
 * 弥生のシェアが個人事業主で 55%（MM総研 2025）と最も大きく、
 * この形式は他社ソフトも読めるため、事実上の共通形式になっています。
 * 項目の並びは弥生公式の「仕訳データの項目と記述形式」に従っています。
 *
 * <p><b>1 行 = 1 明細ではなく、1 行 = 費目×税率×控除率のまとまり。</b><br>
 * レシート 1 枚に食材 8% が 5 行あっても、仕訳としては 1 行で足ります。
 * 明細をそのまま流すと、税理士の帳簿が店の買い物リストで埋まります。
 * <b>まとめる粒度は「税区分が同じかどうか」</b>で、そこが分かれれば
 * 消費税の計算に必要な情報は失われません。
 *
 * <p><b>貸方は買掛金ではなく現金・未払金にしない。</b>
 * 支払方法（現金／カード／振替）で貸方科目が変わりますが、
 * その対応も事務所の科目体系次第なので、対応表（{@link JournalRule}）と
 * 同じ考え方で画面から直せるようにしています（当面は支払方法から素直に決める）。
 */
@Service
public class JournalExportService {

    /** 弥生の日付形式。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 識別フラグ。弥生では 2000 番台が「仕訳」を表す。
     * 2110 = 単一仕訳（借方・貸方が 1 対 1）。
     */
    private static final String FLAG_SIMPLE_JOURNAL = "2110";

    /** 対応表に無い組み合わせに使う仮の科目。CSV 上ですぐ見つかるようにする。 */
    private static final String UNMAPPED_ACCOUNT = "未設定";

    private final PurchaseRepository purchases;
    private final JournalRuleRepository rules;

    public JournalExportService(PurchaseRepository purchases, JournalRuleRepository rules) {
        this.purchases = purchases;
        this.rules = rules;
    }

    /**
     * 指定した月の仕入れを弥生形式の仕訳 CSV にする。
     *
     * <p>削除済みは含めません。帳簿に載せてはいけない記録だからです
     * （検索では出せる必要がありますが、それは別の話）。
     */
    @Transactional(readOnly = true)
    public String exportYayoi(YearMonth month) {
        List<Purchase> found = purchases.findForPeriodWithLines(month.atDay(1), month.atEndOfMonth());
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');   // BOM。無いと日本語 Excel が文字化けする

        for (Purchase purchase : found) {
            if (purchase.isDeleted()) {
                continue;
            }
            for (JournalLine line : groupLines(purchase)) {
                csv.append(toYayoiRow(purchase, line)).append("\r\n");
            }
        }
        return csv.toString();
    }

    /**
     * レシート 1 枚を、税区分ごとにまとめた仕訳の行に畳む。
     *
     * <p>まとめる鍵は「費目 × 税率」。控除率はレシート単位で決まる
     * （証憑区分がレシート単位なので）ため、鍵に含めなくてもぶれません。
     */
    List<JournalLine> groupLines(Purchase purchase) {
        List<JournalLine> grouped = new ArrayList<>();
        for (PurchaseLine line : purchase.getLines()) {
            JournalLine target = null;
            for (JournalLine candidate : grouped) {
                if (candidate.category == line.getCategory()
                        && candidate.taxRatePercent == line.getTaxRatePercent()) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                target = new JournalLine(line.getCategory(), line.getTaxRatePercent());
                grouped.add(target);
            }
            target.amount += line.getAmount();
            target.taxAmount += line.effectiveTaxAmount();
        }
        return grouped;
    }

    /**
     * 弥生 25 項目の 1 行を組み立てる。
     *
     * <p>並びは公式仕様のとおり:
     * <pre>
     *  1 識別フラグ      2 伝票No.      3 決算        4 取引日付     5 借方勘定科目
     *  6 借方補助科目    7 借方部門     8 借方税区分   9 借方金額    10 借方税金額
     * 11 貸方勘定科目   12 貸方補助科目 13 貸方部門   14 貸方税区分  15 貸方金額
     * 16 貸方税金額     17 摘要        18 番号       19 期日       20 タイプ
     * 21 生成元         22 仕訳メモ    23 付箋1      24 付箋2      25 調整
     * </pre>
     * 使わない項目も<b>空欄として必ず出す</b>こと。25 個そろっていないと弾かれます。
     */
    private String toYayoiRow(Purchase purchase, JournalLine line) {
        Optional<JournalRule> rule = rules.find(
                line.category, line.taxRatePercent, purchase.getDeductionRatePercent());

        String account = rule.map(JournalRule::getAccountName).orElse(UNMAPPED_ACCOUNT);
        String taxClass = rule.map(JournalRule::getTaxClassName).orElse(UNMAPPED_ACCOUNT);

        String[] row = new String[25];
        java.util.Arrays.fill(row, "");
        row[0] = FLAG_SIMPLE_JOURNAL;                       // 識別フラグ
        row[3] = purchase.getPurchasedOn().format(DATE);    // 取引日付
        row[4] = account;                                   // 借方勘定科目
        row[7] = taxClass;                                  // 借方税区分
        row[8] = String.valueOf(line.amount);               // 借方金額（税込）
        row[9] = String.valueOf(line.taxAmount);            // 借方税金額
        row[10] = creditAccount(purchase);                  // 貸方勘定科目
        row[13] = "対象外";                                  // 貸方税区分（支払側は課税対象外）
        row[14] = String.valueOf(line.amount);              // 貸方金額
        row[15] = "0";                                      // 貸方税金額
        row[16] = summary(purchase, line);                  // 摘要
        row[19] = "0";                                      // タイプ（0=通常）
        row[24] = "no";                                     // 調整

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(row[i]));
        }
        return sb.toString();
    }

    /** 支払方法から貸方の科目を決める。 */
    private String creditAccount(Purchase purchase) {
        if (purchase.getPaymentMethod() == null) {
            return "現金";
        }
        return switch (purchase.getPaymentMethod()) {
            case CASH -> "現金";
            case CREDIT_CARD -> "未払金";
            case BANK_TRANSFER -> "普通預金";
            case E_MONEY -> "未払金";
            case OTHER -> "現金";
        };
    }

    /**
     * 摘要。<b>帳簿を見た人が、証憑にたどり着けること</b>が目的。
     *
     * <p>経過措置を使った仕入れは、帳簿にその旨を書くことが<b>義務</b>です
     * （「80%控除対象」など）。ここで自動的に入れておきます。
     */
    private String summary(Purchase purchase, JournalLine line) {
        StringBuilder sb = new StringBuilder();
        sb.append(purchase.getStoreName());
        sb.append(' ').append(line.category.getLabel());
        int deduction = purchase.getDeductionRatePercent();
        if (deduction < 100) {
            sb.append(' ').append(deduction > 0 ? deduction + "%控除対象" : "控除対象外");
        }
        if (purchase.getRegNumber() != null) {
            sb.append(' ').append(purchase.getRegNumber());
        }
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }

    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 対応表に無い組み合わせを洗い出す。
     *
     * <p>これを画面に出さないと、<b>「未設定」と書かれた CSV を会計ソフトに
     * 取り込んでから気づく</b>ことになります。出す前に分かるほうがずっと安い。
     */
    @Transactional(readOnly = true)
    public List<String> missingRules(YearMonth month) {
        List<String> missing = new ArrayList<>();
        for (Purchase purchase : purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth())) {
            if (purchase.isDeleted()) {
                continue;
            }
            for (JournalLine line : groupLines(purchase)) {
                boolean found = rules.find(line.category, line.taxRatePercent,
                        purchase.getDeductionRatePercent()).isPresent();
                if (!found) {
                    String label = line.category.getLabel() + " / " + line.taxRatePercent
                            + "% / 控除" + purchase.getDeductionRatePercent() + "%";
                    if (!missing.contains(label)) {
                        missing.add(label);
                    }
                }
            }
        }
        return missing;
    }

    /** 仕訳 1 行ぶんの中身（費目×税率でまとめたもの）。 */
    static final class JournalLine {
        final PurchaseCategory category;
        final int taxRatePercent;
        int amount;
        int taxAmount;

        JournalLine(PurchaseCategory category, int taxRatePercent) {
            this.category = category;
            this.taxRatePercent = taxRatePercent;
        }
    }

    /** 出力するファイル名。 */
    public String fileNameFor(YearMonth month) {
        return "shiwake-" + month + ".csv";
    }

    /** その月に仕訳が何行できるか（画面の事前表示用）。 */
    @Transactional(readOnly = true)
    public int countRows(YearMonth month) {
        int rows = 0;
        for (Purchase purchase : purchases.findForPeriodWithLines(
                month.atDay(1), month.atEndOfMonth())) {
            if (!purchase.isDeleted()) {
                rows += groupLines(purchase).size();
            }
        }
        return rows;
    }

    /** 期間の指定に使う（画面から月を渡す）。 */
    public LocalDate firstDayOf(YearMonth month) {
        return month.atDay(1);
    }
}
