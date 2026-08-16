package jp.komeko.order.service.dto;

/**
 * 商品別の販売実績（売れ筋ランキング用）。
 *
 * @param menuItemName 注文時点の商品名
 * @param quantity     販売個数
 * @param amount       売上金額（税込・円）
 */
public record ItemSales(String menuItemName, Long quantity, Long amount) {

    public long qty() {
        return quantity == null ? 0L : quantity;
    }

    public long sales() {
        return amount == null ? 0L : amount;
    }
}
