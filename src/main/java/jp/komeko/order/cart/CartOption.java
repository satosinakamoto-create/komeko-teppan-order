package jp.komeko.order.cart;

import java.io.Serializable;

/**
 * カートに入っている商品に付いたオプション 1 つ。
 *
 * <p>カートはセッション（サーバのメモリ）に保存されるため、
 * {@link Serializable} を実装しておきます。
 * セッションをファイルや Redis に逃がす構成にしたとき、
 * これが無いと保存できずエラーになります。
 */
public record CartOption(
        Long choiceId,
        String groupName,
        String choiceName,
        int extraPrice
) implements Serializable {

    /** 「チーズ追加 +150円」のような表示文字列。 */
    public String display() {
        if (extraPrice == 0) {
            return choiceName;
        }
        String sign = extraPrice > 0 ? "+" : "−";
        return "%s %s%,d円".formatted(choiceName, sign, Math.abs(extraPrice));
    }
}
