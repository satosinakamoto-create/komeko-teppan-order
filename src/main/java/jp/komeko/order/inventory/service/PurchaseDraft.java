package jp.komeko.order.inventory.service;

import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.domain.PaymentMethod;
import jp.komeko.order.inventory.domain.PurchaseCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 保存しようとしている 1 枚ぶんの入力内容。
 *
 * <p>画面のフォームクラスをそのままサービスに渡さないための、間の入れ物です。
 * こうしておくと、あとから同じ処理を API から呼びたくなったときに、
 * 画面の都合（文字列で受け取る、チェックボックスの有無）が混ざりません。
 *
 * @param purchasedOn        取引年月日
 * @param receivedOn         受領日。null なら取引日と同じ扱い
 * @param storeName          取引先名
 * @param totalAmount        レシートに印字された合計（税込・円）
 * @param paymentMethod      支払い方法
 * @param registrationNumber 登録番号（整形前の生文字列でよい）
 * @param evidenceType       証憑の区分。人が確認画面で選んだ値
 * @param imagePath          保存済みのレシート画像の公開パス
 * @param ocrJson            AI 読取の生の応答
 * @param memo               メモ
 * @param equivalenceChecked 「紙と見比べた」にチェックが入っているか
 * @param lines              明細行
 */
public record PurchaseDraft(
        LocalDate purchasedOn,
        LocalDate receivedOn,
        String storeName,
        int totalAmount,
        PaymentMethod paymentMethod,
        String registrationNumber,
        EvidenceType evidenceType,
        String imagePath,
        String ocrJson,
        String memo,
        boolean equivalenceChecked,
        List<LineDraft> lines
) {

    /**
     * 明細 1 行ぶんの入力内容。
     *
     * @param itemText       品名
     * @param quantity       個数（null 可）
     * @param amount         行の合計（税込・円）
     * @param taxRatePercent 行の税率（%）
     * @param taxAmount      行の消費税額。レシートに印字がなければ null
     * @param category       費目
     */
    public record LineDraft(
            String itemText,
            BigDecimal quantity,
            int amount,
            int taxRatePercent,
            Integer taxAmount,
            PurchaseCategory category
    ) {
    }
}
