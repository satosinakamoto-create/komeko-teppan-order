package jp.komeko.order.domain;

/**
 * 伝票（来店）の状態。
 *
 * <pre>
 *   OPEN   ご案内中。追加注文を受け付ける。
 *     ↓  「お会計」
 *   CLOSED 会計済み。金額が確定し、以後は変更できない。
 * </pre>
 */
public enum SessionStatus {

    OPEN("ご案内中"),
    CLOSED("会計済み");

    private final String label;

    SessionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return this == OPEN;
    }
}
