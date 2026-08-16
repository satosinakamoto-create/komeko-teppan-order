package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * お客さん向け画面だけで使う共通モデル。
 *
 * <p>{@code basePackages} を指定しているので、このパッケージのコントローラにだけ効きます。
 * 厨房やサイネージの画面でカートを触ると、そこでも無駄にセッションが作られてしまうため、
 * わざと適用範囲を絞っています。
 */
@ControllerAdvice(basePackages = "jp.komeko.order.web.customer")
public class CustomerModelAttributes {

    private final Cart cart;

    public CustomerModelAttributes(Cart cart) {
        this.cart = cart;
    }

    /** ヘッダーのカートバッジに出す点数。 */
    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getTotalQuantity();
    }

    /** 画面下の固定バーに出す合計金額（税込・円）。 */
    @ModelAttribute("cartTotal")
    public int cartTotal() {
        return cart.getTotalAmount();
    }
}
