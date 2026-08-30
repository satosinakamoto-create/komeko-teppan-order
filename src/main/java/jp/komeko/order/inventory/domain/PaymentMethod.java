package jp.komeko.order.inventory.domain;

/**
 * 支払い方法。
 *
 * <p><b>なぜ記録するのか</b><br>
 * 帳簿では「何を買ったか」と同じくらい「どう払ったか」が要ります。
 * 現金なら貸方は現金、カードなら未払金、口座振替なら普通預金、と
 * 仕訳の相手方が変わるからです。ここを記録していないと、
 * 仕訳を書き出すときに人が 1 枚ずつ思い出すことになります。
 */
public enum PaymentMethod {

    CASH("現金"),
    CREDIT_CARD("クレジットカード"),
    E_MONEY("電子マネー・QR決済"),
    BANK_TRANSFER("口座振替・振込"),
    OTHER("その他");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
