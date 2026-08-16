package jp.komeko.order.service;

import java.util.List;

/**
 * 注文を受け付けられなかったときに投げる例外。
 *
 * <p>「品切れになっていた」「受付時間外だった」など、
 * <b>お客さんにそのまま見せてよいメッセージ</b>を複数持てるようにしています。
 *
 * <p>{@link RuntimeException} を継承した非チェック例外にしているのは、
 * コントローラまで素通しで投げ上げたいためです（{@code throws} の記述が不要）。
 */
public class OrderRejectedException extends RuntimeException {

    private final List<String> reasons;

    public OrderRejectedException(String reason) {
        this(List.of(reason));
    }

    public OrderRejectedException(List<String> reasons) {
        super(String.join(" / ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
