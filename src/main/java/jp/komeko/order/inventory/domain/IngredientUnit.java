package jp.komeko.order.inventory.domain;

/**
 * 食材を数える単位。
 *
 * <p><b>なぜ自由入力ではなく決め打ちなのか</b><br>
 * 「g」「ｇ」「グラム」が別の単位として並ぶと、在庫の足し算が静かに壊れます。
 * 数え方は店ごとにそう多くないので、選択肢にしてしまうほうが安全です。
 *
 * <p>足りない単位が出てきたら、この enum に 1 行足せば画面の選択肢にも増えます。
 * ただし<b>すでに使われている単位の意味を変えてはいけません</b>
 * （過去の仕入れ・棚卸しの数字がそのまま化けます）。
 *
 * <p><b>重さ・かさ と 個数 は別物</b><br>
 * {@link #isDivisible()} が false の単位（個・パック・袋…）は、
 * 現場で「0.3 個」と数えることがありません。画面ではここを見て、
 * 小数の入力に注意を促します。禁止まではしません
 * （半分だけ使った、を記録したい場面が実在するため）。
 */
public enum IngredientUnit {

    /** グラム。肉・野菜など重さで管理するもの。 */
    GRAM("g", "グラム", true),

    /** ミリリットル。ソース・油・酒など。 */
    MILLILITER("ml", "ミリリットル", true),

    /** 個。卵・レモンなど 1 つ 2 つと数えるもの。 */
    PIECE("個", "個", false),

    /** パック。エリンギ 1 パックなど、袋詰めのまま数えるもの。 */
    PACK("パック", "パック", false),

    /** 袋。米粉 1 袋など。 */
    BAG("袋", "袋", false),

    /** 本。ビール 1 本、大根 1 本など。 */
    BOTTLE("本", "本", false),

    /** 束。ほうれん草 1 束など。 */
    BUNCH("束", "束", false);

    private final String symbol;
    private final String label;
    private final boolean divisible;

    IngredientUnit(String symbol, String label, boolean divisible) {
        this.symbol = symbol;
        this.label = label;
        this.divisible = divisible;
    }

    /** 数字のうしろに付ける短い表記（「120 g」の「g」）。 */
    public String getSymbol() {
        return symbol;
    }

    /** 選択肢に出す名前。 */
    public String getLabel() {
        return label;
    }

    /** 小数で数えるのが自然な単位か。 */
    public boolean isDivisible() {
        return divisible;
    }
}
