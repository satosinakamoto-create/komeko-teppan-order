package jp.komeko.order.service.dto;

/**
 * SSE でブラウザへ送るイベントの中身。
 *
 * <p>Spring が自動で JSON に変換して送ってくれるので、
 * ブラウザ側では {@code JSON.parse(e.data)} で読めます。
 *
 * @param type        イベント種別（created / status-changed / canceled）
 * @param orderId     注文 ID
 * @param orderNumber 呼び出し番号
 * @param status      変更後の状態（enum 名）
 */
public record OrderEvent(String type, Long orderId, int orderNumber, String status) {

    public static OrderEvent created(Long id, int number) {
        return new OrderEvent("created", id, number, "RECEIVED");
    }

    public static OrderEvent statusChanged(Long id, int number, String status) {
        return new OrderEvent("status-changed", id, number, status);
    }
}
