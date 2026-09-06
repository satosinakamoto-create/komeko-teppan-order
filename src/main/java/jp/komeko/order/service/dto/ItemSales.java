package jp.komeko.order.service.dto;

/**
 * 商品別の販売実績（売れ筋ランキング用）。
 *
 * @param menuItemName 注文時点の商品名
 * @param categoryName いまのカテゴリ名。商品を消していれば null
 * @param quantity     販売個数
 * @param amount       売上金額（税込・円）
 */
public record ItemSales(String menuItemName, String categoryName, Long quantity, Long amount) {

    public long qty() {
        return quantity == null ? 0L : quantity;
    }

    public long sales() {
        return amount == null ? 0L : amount;
    }

    /**
     * 画面に出すカテゴリ名。
     *
     * <p><b>null は「その商品がもう無い」という意味</b>です。
     * 注文明細は商品名を写して持っているので、商品を消しても売上には残ります。
     * ところがカテゴリは<b>いまの商品</b>から引くので、消された品では引けません。
     *
     * <p>そこを空欄にすると「カテゴリを付け忘れた品」と見分けが付かないので、
     * はっきりそう書きます。
     */
    public String categoryLabel() {
        return categoryName == null ? "（削除された商品）" : categoryName;
    }
}
