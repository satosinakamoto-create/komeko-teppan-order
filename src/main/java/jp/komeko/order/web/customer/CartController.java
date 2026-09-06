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

    /**
     * 品を注文リストに入れたときの札の文言（設計 暗25 / {@code 370:3959}）。
     *
     * <p><b>改行はデザインです。</b>設計では「注文リストに追加しました」と
     * 「（まだ注文は確定していません）」が別々の行に置かれています。
     * 幅まかせの折り返しに任せると「…確定し／ていません」のように
     * 意味の切れない場所で折れ、しかも折り返す位置は端末の幅で変わります
     * （390 と 360 で違う場所で折れる）。
     * 出す側で切っておけば、どの端末でも同じ 2 行になります。
     *
     * <p>改行をそのまま出すのは {@code app.css} の
     * {@code .theme-night .alert--success}（{@code white-space: pre-line}）です。
     * 片方だけ直すと、改行が空白 1 個に潰れて 1 行に戻ります。
     *
     * <p><b>2 か所から使うので定数にしています。</b>
     * お食事は {@link #add}、おしぼりなどのサービスは
     * {@code ServiceController} から入ります。文言を書き写すと、
     * 片方だけ直したときに<b>同じ操作なのに違う札が出ます</b>。
     *
     * <p>「追加した」だけでは注文が入ったと勘違いされるので、
     * まだ確定していないことを必ず書き添えます。
     */
    public static final String ADDED_TO_CART_MESSAGE =
            "注文リストに追加しました\n（まだ注文は確定していません）";

    /**
     * チェックボックスとラジオ、2 通りの名前で届いた選択肢を 1 本のリストにまとめる。
     *
     * <p><b>なぜ 2 通りあるのか</b>は {@link #add} の説明を参照してください。
     * HTML のラジオは「name が同じもの＝1 つのグループ」という決まりなので、
     * すべて {@code choiceIds} にすると、サイズとソースを同時に選べなくなります。
     *
     * <p><b>なぜ静的メソッドとして外に出しているのか</b><br>
     * スタッフが卓に代わって注文を入れる画面（{@code HallController}）も、
     * まったく同じ形のフォームを送ります。接頭辞と取り出し方を両方に書き写すと、
     * 片方だけ直したときに<b>選択肢が黙って落ちます</b>。
     * 落ちても画面はエラーにならず、注文だけが素の状態で通るので気づけません。
     * 送る側の決まりと受ける側の解釈は、必ず同じ場所に置きます。
     *
     * @param choiceIds チェックボックスから届いたぶん（null 可）
     * @param allParams リクエストのパラメータ全部。ここからラジオのぶんを拾う
     */
    public static List<Long> mergeChoiceIds(List<Long> choiceIds, Map<String, String> allParams) {
        List<Long> selected = new ArrayList<>();
        if (choiceIds != null) {
            selected.addAll(choiceIds);
        }
        if (allParams == null) {
            return selected;
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
        return selected;
    }

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
        List<Long> selected = mergeChoiceIds(choiceIds, allParams);

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
            redirectAttributes.addFlashAttribute("flashSuccess", ADDED_TO_CART_MESSAGE);
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
