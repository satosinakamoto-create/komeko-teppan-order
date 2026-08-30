package jp.komeko.order.inventory.domain;

/**
 * 棚卸しの種類。<b>在庫計算の起点になるか、途中の増減になるか</b>の違い。
 */
public enum StocktakeType {

    /**
     * 実測リセット。「いま数えたら 1200g あった」。
     *
     * <p><b>ここが計算の起点になります。</b>これより前の仕入れも消費も、
     * すべてこの実測値に織り込み済みとして忘れます。
     * だから理論値と実測がずれていても、棚卸しをすれば必ずつじつまが合います。
     *
     * <p>ずれた分（打ち粉・まかない・目分量のブレ）は消えるのではなく、
     * 「理論値と実測の差」として記録に残り、あとから傾向が読めます。
     */
    RESET("棚卸し", "いま数えた実際の量。ここから計算をやり直します"),

    /**
     * 増減の補正。「3 パック捨てた」「まかないで 200g 使った」。
     *
     * <p>起点は動かさず、そこからの増減として積みます。負の数を入れます。
     * 捨てたことを「棚卸しでつじつまを合わせる」のではなく<b>事実として残す</b>ため、
     * 別の種類にしてあります。あとで「先月の廃棄はいくらぶんか」が出せます。
     */
    ADJUST("増減", "捨てた・まかないで使ったなどの増減。減らすときは負の数");

    private final String label;
    private final String description;

    StocktakeType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
