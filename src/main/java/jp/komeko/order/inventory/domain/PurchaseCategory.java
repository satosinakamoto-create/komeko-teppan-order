package jp.komeko.order.inventory.domain;

/**
 * 仕入れ・経費の分類。レシートの<b>明細行ごと</b>に持つ。
 *
 * <p><b>なぜレシート単位ではなく行単位なのか</b><br>
 * スーパーで買い出しをすると、1 枚のレシートに「キャベツ（食材）」と
 * 「洗剤（消耗品）」が並びます。レシート 1 枚を 1 つの分類に決めつけると、
 * どちらかが必ず嘘になります。
 *
 * <p><b>税率とは別に持つ理由</b><br>
 * いまは「食材 = 軽減税率 8%」がほぼ一致しますが、これは<b>今の税制がそうなっているだけ</b>です。
 * 2026-08-05 の閣議決定どおり飲食料品が 1% になれば、この対応は崩れます。
 * だから分類は「経営上どの費目か」、税率は「その日レシートに印字されていた事実」として
 * 別々に持ちます。制度が変わっても過去の記録は正しいままです。
 *
 * @see PurchaseLine
 */
public enum PurchaseCategory {

    /** 食材。原価率の分子になるのはこれだけ。 */
    FOOD("食材", true),

    /** 飲料・酒類。酒は軽減税率の対象外（標準税率）。 */
    DRINK("飲料・酒", false),

    /** 消耗品（割り箸・容器・洗剤など）。 */
    SUPPLIES("消耗品", false),

    /** 水道光熱費。明細のない請求書もここに 1 行で入れる。 */
    UTILITIES("光熱費", false),

    /** その他。 */
    OTHER("その他", false);

    private final String label;
    private final boolean foodCost;

    PurchaseCategory(String label, boolean foodCost) {
        this.label = label;
        this.foodCost = foodCost;
    }

    /** 画面に出す日本語名。 */
    public String getLabel() {
        return label;
    }

    /**
     * 実際原価率の分子に含める分類か。
     *
     * <p>原価率は「<b>食材</b>仕入総額 ÷ 売上」です。
     * 洗剤や電気代まで分子に入れると、それは原価率ではなく経費率になってしまいます。
     */
    public boolean isFoodCost() {
        return foodCost;
    }
}
