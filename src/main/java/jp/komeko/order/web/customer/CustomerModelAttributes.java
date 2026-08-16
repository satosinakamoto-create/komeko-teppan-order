package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.TableService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * お客さん向け画面だけで使う共通モデル。
 *
 * <p>{@code basePackages} を指定しているので、このパッケージのコントローラにだけ効きます。
 * 厨房や管理の画面でカートを触ると、そこでも無駄にセッションが作られてしまうため、
 * わざと適用範囲を絞っています。
 */
@ControllerAdvice(basePackages = "jp.komeko.order.web.customer")
public class CustomerModelAttributes {

    private final Cart cart;
    private final TableContext tableContext;
    private final TableService tableService;

    public CustomerModelAttributes(Cart cart, TableContext tableContext, TableService tableService) {
        this.cart = cart;
        this.tableContext = tableContext;
        this.tableService = tableService;
    }

    /** ヘッダーのカートバッジに出す点数。 */
    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getTotalQuantity();
    }

    /** 画面下の固定バーに出すカートの合計金額（税込・円）。 */
    @ModelAttribute("cartTotal")
    public int cartTotal() {
        return cart.getTotalAmount();
    }

    /** いまいる卓（QR を読んでいなければ null）。 */
    @ModelAttribute("tableName")
    public String tableName() {
        return tableContext.getTableName();
    }

    /** 卓の QR トークン（画面内のリンクを組み立てるのに使う）。 */
    @ModelAttribute("tableToken")
    public String tableToken() {
        return tableContext.getAccessToken();
    }

    /**
     * いま開いている伝票。
     *
     * <p>ヘッダーに「お席の合計」を出すために毎回引いています。
     * 1 リクエストにつき 1 クエリ増えますが、
     * お客さんが「いくらになっているか」をいつでも見られることのほうが
     * 価値が大きいと判断しました。
     *
     * <p><b>属性名を {@code bill} にしている理由</b><br>
     * Thymeleaf では {@code ${session}} が「HTTP セッションの属性マップ」を指す
     * 予約された名前になっています。モデルに {@code session} という名前を入れると
     * どちらを指しているのか分からなくなるため、別名にしています。
     * （{@code param}、{@code request}、{@code application} も同様に予約されています）
     */
    @ModelAttribute("bill")
    public TableSession bill() {
        if (!tableContext.isBound()) {
            return null;
        }
        return tableService.currentSession(tableContext.getTableId()).orElse(null);
    }
}
