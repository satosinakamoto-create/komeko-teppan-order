package jp.komeko.order.inventory.domain;

/**
 * 在庫を動かした理由。
 *
 * <p><b>「なぜ減ったか」を残すと、あとで意味が出ます。</b>
 * 廃棄とまかないを区別せずに一括で減らしてしまうと、
 * 「先月いくら捨てたのか」が二度と分かりません。
 * 入力の手間はほぼ同じなので、最初から分けておきます。
 */
public enum StocktakeReason {

    /** 数え直した。{@link StocktakeType#RESET} と組で使う。 */
    STOCKTAKE("棚卸し"),

    /** 傷んだ・落とした・売れ残りを捨てた。 */
    WASTE("廃棄"),

    /** まかない・試作で使った。 */
    STAFF_MEAL("まかない・試作"),

    /** レシピにない使い方（イベント、差し入れなど）。 */
    OTHER("その他");

    private final String label;

    StocktakeReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
