package jp.komeko.order.inventory.web;

import java.math.BigDecimal;

/**
 * 数量が列（precision=12, scale=3）に収まるかの手前検査。
 *
 * <p><b>なぜフォームの {@code @Digits} だけで済まないのか</b><br>
 * 在庫まわりには {@code @RequestParam BigDecimal} で直接受けている口が
 * 2 つある（レシピの分量修正・入り数の学習）。{@code @Valid} なフォームと
 * 違い、素の {@code @RequestParam} には Bean Validation が効かない。
 * 検査を置かないと、13 桁の入力が DB の桁あふれ例外になってそのまま 500
 * （2026-09-07 の全体点検 #4）。
 *
 * <p>既存の「0 以下チェック」と同じ場所（サービスを呼ぶ手前）で使うこと。
 */
final class QuantityDigits {

    /** エラーメッセージの共通の後半。呼び出し側で「◯◯が大きすぎます。」に続ける。 */
    static final String LIMIT_NOTE = "整数は 9 桁まで・小数は 3 桁までで入力してください";

    private QuantityDigits() {
    }

    /**
     * precision=12, scale=3 の列からあふれるか。
     *
     * <p>{@code stripTrailingZeros} を通すのは、「100.000」を
     * 小数 3 桁と数えて弾かないため（値としては整数 100）。
     */
    static boolean overflows(BigDecimal value) {
        if (value == null) {
            return false;   // null の扱い（必須かどうか）は呼び出し側の仕事
        }
        BigDecimal n = value.stripTrailingZeros();
        int fractionDigits = Math.max(0, n.scale());
        int integerDigits = n.precision() - n.scale();
        return fractionDigits > 3 || integerDigits > 9;
    }
}
