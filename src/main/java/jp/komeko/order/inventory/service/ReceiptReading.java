package jp.komeko.order.inventory.service;

import java.util.List;

/**
 * AI がレシートから読み取った結果。<b>まだ下書きです。</b>
 *
 * <p>この値をそのまま保存することはありません。必ず人が確認画面で見て、
 * 直してから保存します。だからどの項目も null を許します。
 * 読めなかったものを無理に埋めるより、空欄で人に渡すほうが安全だからです。
 *
 * <p><b>数量のないレシートは異常ではなく普通</b><br>
 * 「エリンギ 120」のように単価も数量も書かれていないレシートは珍しくありません。
 * それを読み取り失敗として扱うと、実運用では毎回エラーになります。
 *
 * @param storeName          店名
 * @param purchasedOn        取引年月日（ISO 形式の文字列。日付として読めない値もあるので String）
 * @param registrationNumber 登録番号（T + 13 桁）。印字がなければ null
 * @param totalAmount        レシートの合計金額（税込・円）
 * @param lines              明細行
 * @param rawJson            AI の生の応答。あとから読み違いを検証するために丸ごと残す
 */
public record ReceiptReading(
        String storeName,
        String purchasedOn,
        String registrationNumber,
        Integer totalAmount,
        List<Line> lines,
        String rawJson
) {

    /** 読み取りに失敗した／読取機能が無効なときの空の結果。 */
    public static ReceiptReading empty() {
        return new ReceiptReading(null, null, null, null, List.of(), null);
    }

    /** 1 行も読めていないか。 */
    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }

    /**
     * 明細 1 行ぶんの読み取り結果。
     *
     * @param itemText       品名（レシートの文字そのまま）
     * @param quantity       個数。印字がなければ null
     * @param amount         行の合計金額（税込・円）
     * @param taxRatePercent 行の税率（%）。印字や軽減マークから判断。分からなければ null
     * @param taxAmount      行の消費税額（円）。<b>印字があるときだけ</b>。無ければ null
     *                       （適格簡易請求書は税額の印字が無くても合法）
     * @param reducedMark    軽減税率の印（※ など）が付いていたか
     */
    public record Line(
            String itemText,
            java.math.BigDecimal quantity,
            Integer amount,
            Integer taxRatePercent,
            Integer taxAmount,
            boolean reducedMark
    ) {
    }
}
