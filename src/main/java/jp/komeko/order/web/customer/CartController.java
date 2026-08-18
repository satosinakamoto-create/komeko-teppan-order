package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * カートの操作。
 *
 * <p><b>PRG パターン（Post/Redirect/Get）</b><br>
 * 追加・削除のような「更新」の POST では、処理が終わったら必ずリダイレクトします。
 * そのまま HTML を返すと、お客さんがブラウザを更新したときに
 * 同じ POST がもう一度送られて二重登録になってしまうためです。
 * 画面に出したいメッセージは {@link RedirectAttributes} に載せて次の画面へ渡します
 * （フラッシュ属性。1 回表示したら自動で消えます）。
 */
@Controller
@RequestMapping("/cart")
public class CartController {

    /** ラジオボタン（1 つだけ選ぶオプション）に付ける name の接頭辞。 */
    public static final String SINGLE_CHOICE_PREFIX = "single_";

    private final Cart cart;
    private final CartService cartService;
    private final ShopSettingService shopSettingService;

    public CartController(Cart cart, CartService cartService, ShopSettingService shopSettingService) {
        this.cart = cart;
        this.cartService = cartService;
        this.shopSettingService = shopSettingService;
    }

    /** カート画面。 */
    @GetMapping
    public String view(Model model) {
        ShopSetting setting = shopSettingService.currentReadOnly();
        LocalDateTime now = LocalDateTime.now();

        // 消費税・テーブルチャージは「伝票（卓）」の単位で計算するので、
        // カート画面では今回追加ぶんの小計だけを見せる。
        model.addAttribute("cart", cart);
        model.addAttribute("accepting", setting.isOrderAcceptable(now));
        model.addAttribute("rejectReason", setting.orderRejectReason(now));
        return "customer/cart";
    }

    /**
     * 商品詳細画面からの「カートに入れる」。
     *
     * <p><b>選択肢を 2 種類の名前で受けている理由</b><br>
     * 複数選べるオプション（トッピング）はチェックボックスなので
     * すべて {@code choiceIds} という同じ名前で送れます。
     * 一方 1 つだけ選ぶオプション（サイズなど）はラジオボタンで、
     * HTML のラジオは<b>名前が同じものが 1 つのグループ</b>になります。
     * すべて {@code choiceIds} にしてしまうと、サイズとソースを
     * 同時に選べなくなってしまうため、
     * ラジオだけ {@code single_グループID} という個別の名前にしています。
     * ここで両方を 1 つのリストにまとめ直します。
     */
    @PostMapping("/add")
    public String add(@RequestParam Long menuItemId,
                      @RequestParam(name = "choiceIds", required = false) List<Long> choiceIds,
                      @RequestParam(defaultValue = "1") int quantity,
                      @RequestParam Map<String, String> allParams,
                      RedirectAttributes redirectAttributes) {
        List<Long> selected = new ArrayList<>();
        if (choiceIds != null) {
            selected.addAll(choiceIds);
        }
        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            if (!entry.getKey().startsWith(SINGLE_CHOICE_PREFIX)) {
                continue;
            }
            try {
                selected.add(Long.valueOf(entry.getValue()));
            } catch (NumberFormatException ignored) {
                // 数値でない値が送られてきた場合は無視する（後段の検証で弾かれる）
            }
        }

        try {
            cartService.addToCart(cart, menuItemId, selected, quantity);
            // 追加したらメニューへ戻す。
            //
            // 以前はここで注文リストへ飛ばしていましたが、
            // 飲食店では「いくつか見て回って、最後にまとめて注文する」のがふつうです。
            // 1 品足すたびにリストへ連れて行かれると、そのたびに戻る操作が要ります。
            // メニューに戻せば続けて選べますし、画面下の固定バーに
            // 「注文リストを見る（N 点）」が出ているので、いつでも確認へ移れます。
            //
            // 「追加した」だけでは注文が入ったと勘違いされるので、
            // まだ確定していないことを必ず書き添えます。
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "注文リストに追加しました（まだ注文は確定していません）");
            return "redirect:/menu";
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
            return "redirect:/items/" + menuItemId;
        } catch (IllegalStateException e) {
            // カートの上限に達したときなど
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
            return "redirect:/items/" + menuItemId;
        }
    }

    /** 個数変更。 */
    @PostMapping("/update")
    public String update(@RequestParam String key, @RequestParam int quantity) {
        cart.changeQuantity(key, quantity);
        return "redirect:/cart";
    }

    /** 1 行削除。 */
    @PostMapping("/remove")
    public String remove(@RequestParam String key, RedirectAttributes redirectAttributes) {
        cart.remove(key);
        redirectAttributes.addFlashAttribute("flashInfo", "商品を削除しました");
        return "redirect:/cart";
    }

    /** カートを空にする。 */
    @PostMapping("/clear")
    public String clear(RedirectAttributes redirectAttributes) {
        cart.clear();
        redirectAttributes.addFlashAttribute("flashInfo", "カートを空にしました");
        return "redirect:/";
    }
}
