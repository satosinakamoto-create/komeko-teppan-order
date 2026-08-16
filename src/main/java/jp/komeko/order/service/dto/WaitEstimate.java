package jp.komeko.order.service.dto;

/**
 * 待ち時間の目安。
 *
 * @param waitingOrders  自分より前に残っている注文の組数
 * @param estimateMinutes 待ち時間の目安（分）
 */
public record WaitEstimate(long waitingOrders, int estimateMinutes) {

    public static WaitEstimate none() {
        return new WaitEstimate(0, 0);
    }

    /** 「約 10 分」のような表示文字列。0 分なら「まもなく」。 */
    public String label() {
        if (estimateMinutes <= 0) {
            return "まもなく";
        }
        return "約 " + estimateMinutes + " 分";
    }
}
