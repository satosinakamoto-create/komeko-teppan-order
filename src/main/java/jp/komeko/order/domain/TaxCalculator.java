package jp.komeko.order.domain;

/**
 * 消費税の計算をまとめたユーティリティ。
 *
 * <p><b>内税（総額表示）とは</b><br>
 * 日本では 2021 年 4 月から、値札に「支払総額」を表示することが義務づけられています。
 * つまりメニューに書く 850 円には、すでに消費税が含まれています。
 * そのため税額は「足す」のではなく「含まれている分を逆算する」計算になります。
 *
 * <pre>
 *   税込 850 円・税率 8% のとき
 *     税額 = 850 × 8 ÷ 108 = 62.96... → 62 円（切り捨て）
 *     税抜 = 850 − 62 = 788 円
 * </pre>
 *
 * <p><b>テイクアウトは 8%</b><br>
 * 飲食料品の持ち帰りは軽減税率（8%）が適用されます。
 * 店内飲食にすると 10% になるので、将来イートインを足すときは
 * 注文ごとに税率を切り替えられるようにしてあります（{@code Order.taxRatePercent}）。
 *
 * <p>このクラスは状態を持たない計算だけの集まりなので、
 * インスタンスを作れないように private コンストラクタを置いています。
 */
public final class TaxCalculator {

    private TaxCalculator() {
        // ユーティリティクラスなのでインスタンス化させない
    }

    /**
     * 税込金額に含まれる消費税額を求める（1 円未満切り捨て）。
     *
     * @param includedTaxAmount 税込金額（円）
     * @param taxRatePercent    税率（%）。8 なら 8%
     * @return 内税額（円）
     */
    public static int includedTax(int includedTaxAmount, int taxRatePercent) {
        if (taxRatePercent <= 0) {
            return 0;
        }
        // int どうしの割り算は自動で切り捨てになる。
        // 先に掛けてから割ることで、精度の落ちない整数計算にしている。
        long tax = (long) includedTaxAmount * taxRatePercent / (100L + taxRatePercent);
        return (int) tax;
    }

    /**
     * 税込金額から税抜相当額を求める。
     */
    public static int netAmount(int includedTaxAmount, int taxRatePercent) {
        return includedTaxAmount - includedTax(includedTaxAmount, taxRatePercent);
    }
}
