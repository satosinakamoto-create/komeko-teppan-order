package jp.komeko.order.service.dto;

import jp.komeko.order.domain.Order;

import java.util.List;

/**
 * 厨房ボードに出す 3 レーン分のデータ。
 *
 * @param received 受付（まだ焼いていない）
 * @param cooking  調理中
 * @param ready    お渡し可（呼び出し中）
 */
public record KitchenBoard(List<Order> received, List<Order> cooking, List<Order> ready) {

    public static KitchenBoard empty() {
        return new KitchenBoard(List.of(), List.of(), List.of());
    }

    /** 未完了の合計件数（画面のタイトルに出す）。 */
    public int activeCount() {
        return received.size() + cooking.size() + ready.size();
    }
}
