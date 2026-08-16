package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.dto.WaitEstimate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * 注文の確定と、注文後の状況確認。
 *
 * <p>お客さんは会員登録をしないので、注文後の控えは
 * {@code /o/{推測できないトークン}} という URL で見てもらいます。
 * このページをブックマークしておけば、あとから何度でも状況を確認できます。
 */
@Controller
public class CheckoutController {

    private final Cart cart;
    private final OrderService orderService;

    public CheckoutController(Cart cart, OrderService orderService) {
        this.cart = cart;
        this.orderService = orderService;
    }

    /**
     * 注文を確定する。
     *
     * <p>成功したら控えページへリダイレクトし、カートを空にします。
     * 失敗したらカート画面に理由を出して戻します
     * （値上げや品切れがあった場合、カートの中身は最新に洗い替えられています）。
     */
    @PostMapping("/checkout")
    public String placeOrder(@RequestParam(required = false) String customerName,
                             @RequestParam(required = false) String note,
                             RedirectAttributes redirectAttributes) {
        try {
            Order order = orderService.placeOrder(cart, customerName, note);
            cart.clear();
            return "redirect:/o/" + order.getPublicToken();
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
            return "redirect:/cart";
        }
    }

    /** 注文控え／状況確認ページ。 */
    @GetMapping("/o/{token}")
    public String orderStatus(@PathVariable String token, Model model) {
        Order order = orderService.findByToken(token)
                .orElseThrow(() -> new OrderService.OrderNotFoundException(token));
        WaitEstimate wait = orderService.estimateWait(order);

        model.addAttribute("order", order);
        model.addAttribute("wait", wait);
        model.addAttribute("statusOrder", List.of(
                OrderStatus.RECEIVED, OrderStatus.COOKING, OrderStatus.READY, OrderStatus.COMPLETED));
        return "customer/order";
    }

    /** お客さん自身によるキャンセル（調理開始前のみ）。 */
    @PostMapping("/o/{token}/cancel")
    public String cancel(@PathVariable String token, RedirectAttributes redirectAttributes) {
        try {
            orderService.cancelByCustomer(token);
            redirectAttributes.addFlashAttribute("flashInfo", "ご注文をキャンセルしました");
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
        }
        return "redirect:/o/" + token;
    }

    /**
     * 状況確認ページが定期的に呼ぶ JSON API。
     *
     * <p>お客さんのスマホは何十台にもなり得るので、SSE で接続を張りっぱなしにせず
     * 5 秒ごとの軽いポーリングにしています。
     * 接続数を抱えずに済み、電波が不安定でも復帰が簡単です。
     *
     * <p>{@code @ResponseBody} を付けると、戻り値が画面名ではなく
     * JSON としてそのまま返されます。
     */
    @GetMapping("/api/public/orders/{token}")
    @ResponseBody
    public Map<String, Object> status(@PathVariable String token) {
        Order order = orderService.findByToken(token)
                .orElseThrow(() -> new OrderService.OrderNotFoundException(token));
        WaitEstimate wait = orderService.estimateWait(order);

        return Map.of(
                "status", order.getStatus().name(),
                "statusLabel", order.getStatus().getCustomerLabel(),
                "orderNumber", order.getOrderNumber(),
                "waitingOrders", wait.waitingOrders(),
                "estimateMinutes", wait.estimateMinutes(),
                "waitLabel", wait.label());
    }
}
