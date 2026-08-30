package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.inventory.config.InventoryProperties;
import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.repository.PurchaseRepository;
import jp.komeko.order.inventory.repository.SalesLookupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 仕入れ・経費の記録と集計。
 *
 * <p><b>ここが「お金の層」の本体です。</b>
 * レシートを 1 枚受け取って、消費税のルールを当てはめ、
 * 電子帳簿保存法の条件を満たす形で保存し、月次で集計して原価率を出す。
 *
 * <p><b>時刻を {@link Clock} から取る理由</b><br>
 * {@code LocalDateTime.now()} を直接呼ぶと、「入力期限を過ぎたら紙の保管が要る」
 * といった<b>日付に依存する処理をテストできません</b>。
 * 時計を外から差し込めるようにしておくと、テストで
 * 「受領から 10 日後に登録した場合」を自由に作れます。
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    /**
     * 少額特例の判定金額（税込・円未満）。
     * 1 商品ごとではなく<b>レシート 1 枚の合計</b>で判定する。
     */
    private static final int SMALL_AMOUNT_THRESHOLD = 10_000;

    /** 少額特例が使える最終日。これを過ぎると原則どおり証憑の保存が要る。 */
    private static final LocalDate SMALL_AMOUNT_SPECIAL_UNTIL = LocalDate.of(2029, 9, 30);

    private final PurchaseRepository purchases;
    private final SalesLookupRepository sales;
    private final TaxRuleService taxRules;
    private final InventoryProperties properties;
    private final Clock clock;

    public PurchaseService(PurchaseRepository purchases,
                           SalesLookupRepository sales,
                           TaxRuleService taxRules,
                           InventoryProperties properties,
                           Clock clock) {
        this.purchases = purchases;
        this.sales = sales;
        this.taxRules = taxRules;
        this.properties = properties;
        this.clock = clock;
    }

    // ========================================================================
    //  記録する
    // ========================================================================

    /**
     * レシート 1 枚を記録する。
     *
     * @param draft   確認画面で人が確定させた内容
     * @param staffId 登録した人（staff_user.id）
     */
    @Transactional
    public Purchase record(PurchaseDraft draft, Long staffId) {
        LocalDateTime storedAt = LocalDateTime.now(clock);
        LocalDate receivedOn = draft.receivedOn() != null ? draft.receivedOn() : draft.purchasedOn();

        Purchase purchase = new Purchase(
                draft.purchasedOn(), receivedOn, draft.storeName(),
                draft.totalAmount(), draft.paymentMethod(), storedAt);

        // ── 登録番号を整えて、どこまで確かめられたかを記録する ──
        applyRegistrationNumber(purchase, draft.registrationNumber());

        // ── 証憑区分と、そこから決まる控除率 ──
        EvidenceType evidenceType = draft.evidenceType() != null
                ? draft.evidenceType()
                : suggestEvidenceType(purchase.getRegNumber(), draft.totalAmount(), draft.purchasedOn());
        purchase.setEvidenceType(evidenceType);
        purchase.setDeductionRatePercent(taxRules.deductionRateOn(evidenceType, draft.purchasedOn()));

        // ── 入力期限（電子帳簿保存法） ──
        purchase.setPaperRetentionRequired(isPastInputDeadline(receivedOn, storedAt.toLocalDate()));

        purchase.setImagePath(draft.imagePath());
        purchase.setOcrJson(draft.ocrJson());
        purchase.setMemo(draft.memo());
        purchase.setCreatedBy(staffId);
        if (draft.equivalenceChecked()) {
            purchase.markEquivalenceChecked(storedAt);
        }

        int lineNo = 1;
        for (PurchaseDraft.LineDraft line : draft.lines()) {
            purchase.addLine(new PurchaseLine(
                    lineNo++, line.itemText(), line.quantity(),
                    line.amount(), line.taxRatePercent(), line.taxAmount(), line.category()));
        }

        Purchase saved = purchases.save(purchase);
        log.info("仕入れを登録しました: id={} 店={} 合計={}円 明細={}行",
                saved.getId(), saved.getStoreName(), saved.getTotalAmount(), saved.getLines().size());
        return saved;
    }

    /**
     * 登録番号を整形し、確かめられたところまでを状態として持たせる。
     *
     * <p>検算に通らなくても弾きません。個人事業者の登録番号には
     * 法人番号の検算式が当てはまらないので、
     * 「合格は加点、不合格は保留」として扱います。
     */
    private void applyRegistrationNumber(Purchase purchase, String raw) {
        if (raw == null || raw.isBlank()) {
            purchase.setRegVerifyStatus(RegistrationVerifyStatus.NONE);
            return;
        }
        purchase.setRegNumberRaw(raw);
        String normalized = RegistrationNumber.normalize(raw);
        if (normalized == null) {
            // 13 桁そろっていない。読み違いの可能性が高いので生の値だけ残す。
            purchase.setRegVerifyStatus(RegistrationVerifyStatus.UNVERIFIED);
            return;
        }
        purchase.setRegNumber(normalized);
        purchase.setRegVerifyStatus(RegistrationNumber.matchesCorporateCheckDigit(normalized)
                ? RegistrationVerifyStatus.CHECK_DIGIT_OK
                : RegistrationVerifyStatus.UNVERIFIED);
    }

    /**
     * 証憑の区分の「たたき台」を出す。<b>最終的に決めるのは人</b>。
     *
     * <p>制度の解釈をシステムに任せきると、細部が変わったときに
     * 気づかないまま間違え続けます。確認画面で人が見て直せる形にしておきます。
     */
    public EvidenceType suggestEvidenceType(String normalizedRegNumber, int totalAmount, LocalDate purchasedOn) {
        if (normalizedRegNumber != null && RegistrationNumber.hasValidFormat(normalizedRegNumber)) {
            return EvidenceType.SIMPLIFIED_INVOICE;
        }
        boolean smallAmountEraStillOpen = purchasedOn != null && !purchasedOn.isAfter(SMALL_AMOUNT_SPECIAL_UNTIL);
        if (smallAmountEraStillOpen && totalAmount < SMALL_AMOUNT_THRESHOLD) {
            // ※ 少額特例が使えるのは基準期間の課税売上高が 1 億円以下などの事業者に限られる。
            //    その判定は店の属性なので、ここでは「候補」として出すだけにとどめる。
            return EvidenceType.BOOK_ONLY_SPECIAL;
        }
        return EvidenceType.NOT_QUALIFIED;
    }

    /**
     * 受領から入力期限を過ぎているか。
     *
     * <p>電子帳簿保存法の「速やか」は概ね 7 営業日ですが、
     * 営業日は店の定休日に左右されて厳密に数えにくいので、
     * より厳しい暦日で判定します（早く警告が出るぶんには害がない）。
     */
    public boolean isPastInputDeadline(LocalDate receivedOn, LocalDate storedOn) {
        if (receivedOn == null || storedOn == null) {
            return false;
        }
        return storedOn.isAfter(receivedOn.plusDays(properties.inputDeadlineDays()));
    }

    /** 論理削除する。行は消さない（削除済みも検索できる必要があるため）。 */
    @Transactional
    public void softDelete(Long id, String reason) {
        purchases.findById(id).ifPresent(p -> {
            p.markDeleted(LocalDateTime.now(clock), reason);
            log.info("仕入れを論理削除しました: id={} 理由={}", id, reason);
        });
    }

    /** 「紙と見比べた」を記録する。 */
    @Transactional
    public void markEquivalenceChecked(Long id) {
        purchases.findById(id).ifPresent(p -> p.markEquivalenceChecked(LocalDateTime.now(clock)));
    }

    // ========================================================================
    //  探す
    // ========================================================================

    /** 検索 3 項目による絞り込み（電子帳簿保存法の検索要件）。 */
    @Transactional(readOnly = true)
    public Page<Purchase> search(LocalDate from, LocalDate to, Integer minAmount, Integer maxAmount,
                                 String storeKeyword, boolean includeDeleted, Pageable pageable) {
        String keyword = (storeKeyword == null || storeKeyword.isBlank()) ? null : storeKeyword.trim();
        return purchases.search(from, to, minAmount, maxAmount, keyword, includeDeleted, pageable);
    }

    /** 1 件を明細つきで読む。 */
    @Transactional(readOnly = true)
    public Purchase findWithLines(Long id) {
        return purchases.findByIdWithLines(id).orElse(null);
    }

    /** 税理士に渡す「例外リスト」。 */
    @Transactional(readOnly = true)
    public List<Purchase> needingAttention(LocalDate from, LocalDate to) {
        return purchases.findNeedingAttention(from, to);
    }

    // ========================================================================
    //  集計する
    // ========================================================================

    /** 指定した月のまとめ（費目別内訳と原価率）。 */
    @Transactional(readOnly = true)
    public PurchaseSummary summarize(YearMonth month) {
        return summarize(month.atDay(1), month.atEndOfMonth());
    }

    /**
     * 期間のまとめ。
     *
     * <p>集計を SQL ではなく Java で回しているのは、この規模なら十分速く、
     * 「食材だけ」「税抜」といった条件がドメインの言葉のまま読めるからです。
     * 件数が万を超えるようなら SQL 側の集計に移します。
     */
    @Transactional(readOnly = true)
    public PurchaseSummary summarize(LocalDate from, LocalDate to) {
        List<Purchase> found = purchases.findForPeriodWithLines(from, to);

        Map<PurchaseCategory, int[]> byCategory = new EnumMap<>(PurchaseCategory.class);
        int totalIncludingTax = 0;
        int totalNet = 0;
        int foodIncludingTax = 0;
        int foodNet = 0;

        for (Purchase purchase : found) {
            for (PurchaseLine line : purchase.getLines()) {
                int[] row = byCategory.computeIfAbsent(line.getCategory(), k -> new int[2]);
                row[0] += line.getAmount();
                row[1] += line.netAmount();

                totalIncludingTax += line.getAmount();
                totalNet += line.netAmount();
                if (line.getCategory().isFoodCost()) {
                    foodIncludingTax += line.getAmount();
                    foodNet += line.netAmount();
                }
            }
        }

        List<PurchaseSummary.CategoryRow> rows = new ArrayList<>();
        for (PurchaseCategory category : PurchaseCategory.values()) {
            int[] row = byCategory.get(category);
            if (row != null) {
                rows.add(new PurchaseSummary.CategoryRow(category, row[0], row[1]));
            }
        }

        // ── 売上（既存の注文データから読むだけ） ──
        long salesGross = 0;
        long salesTax = 0;
        SalesLookupRepository.SalesTotal total = sales.sumSales(from, to, OrderStatus.COMPLETED);
        if (total != null) {
            salesGross = total.getGrossAmount() != null ? total.getGrossAmount() : 0;
            salesTax = total.getTaxAmount() != null ? total.getTaxAmount() : 0;
        }

        return new PurchaseSummary(from, to, found.size(), rows,
                totalIncludingTax, totalNet, foodIncludingTax, foodNet,
                salesGross, salesGross - salesTax);
    }

    /** 今日の日付（テストから差し替えられるよう Clock 経由で取る）。 */
    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
