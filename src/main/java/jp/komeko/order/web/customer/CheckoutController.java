package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.TableService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注文の確定と、お席の伝票の表示。
 *
 * <p>イートインなので、注文するたびに新しい伝票ができるのではなく、
 * <b>同じ卓の 1 枚の伝票にどんどん追加</b>されていきます。
 * お客さんは {@code /bill} でいつでも「いまいくらか」を確認できます。
 */
@Controller
public class CheckoutController {

    private final Cart cart;
    private final TableContext tableContext;
    private final TableService tableService;
    private final OrderService orderService;

    public CheckoutController(Cart cart,
                              TableContext tableContext,
                              TableService tableService,
                              OrderService orderService) {
        this.cart = cart;
        this.tableContext = tableContext;
        this.tableService = tableService;
        this.orderService = orderService;
    }

    /**
     * 注文を確定して、お席の伝票に追加する。
     *
     * <p>失敗したらカート画面に理由を出して戻します
     * （値上げや品切れがあった場合、カートの中身は最新に洗い替えられています）。
     */
    @PostMapping("/checkout")
    public String placeOrder(@RequestParam(required = false) String note,
                             RedirectAttributes redirectAttributes) {
        if (!tableContext.isBound()) {
            return "redirect:/";
        }
        try {
            TableSession session = tableService.requireOpenSession(tableContext.getTableId());
            OrderService.Placed placed = orderService.place(cart, session.getId(), note);
            cart.clear();

            // 売り切れで落ちた品があっても、注文そのものは通っている。
            // 「売り切れました」だけを出すと注文が失敗したように読めるので、
            // 何が落ちて、何が通ったのかを続けて伝える。
            if (placed.soldOutNotices().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashSuccess", "ご注文を承りました。お席までお持ちします。");
            } else {
                redirectAttributes.addFlashAttribute("flashErrors", placed.soldOutNotices());
                // 単位は「点」。残数の案内（「残り 2 点です」）と言葉をそろえる
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "残りの %d 点はご注文を承りました。お席までお持ちします。"
                                .formatted(placed.order().getTotalQuantity()));
            }
            return "redirect:/bill";
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
            return "redirect:/cart";
        }
    }

    /**
     * お席の伝票。これまでの注文と、いまの合計金額を出す。
     */
    @GetMapping("/bill")
    public String bill(Model model) {
        if (!tableContext.isBound()) {
            return "redirect:/";
        }
        TableSession session = tableService.currentSession(tableContext.getTableId()).orElse(null);
        if (session == null) {
            // 会計が済んだ直後など。もう一度 QR から入り直してもらう。
            model.addAttribute("tableName", tableContext.getTableName());
            return "customer/bill-closed";
        }

        model.addAttribute("bill", session);
        model.addAttribute("orders", session.getBillableOrders());
        return "customer/bill";
    }

    /**
     * お客さん自身によるキャンセル（調理開始前のみ）。
     *
     * <p>注文の指定に連番の ID ではなく推測できないトークンを使っているのは、
     * 番号を変えるだけで他の卓の注文を取り消せてしまうのを防ぐためです。
     */
    @PostMapping("/bill/orders/{token}/cancel")
    public String cancel(@PathVariable String token, RedirectAttributes redirectAttributes) {
        try {
            orderService.cancelByCustomer(token);
            redirectAttributes.addFlashAttribute("flashInfo", "ご注文をキャンセルしました");
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
        }
        return "redirect:/bill";
    }

    /**
     * 伝票ページが定期的に呼ぶ JSON API。
     *
     * <p>お客さんのスマホは何十台にもなり得るので、SSE で接続を張りっぱなしにせず
     * 数秒ごとの軽いポーリングにしています。
     * 接続数を抱えずに済み、電波が不安定でも復帰が簡単です。
     */
    @GetMapping("/api/public/bill")
    @ResponseBody
    public Map<String, Object> billStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!tableContext.isBound()) {
            result.put("bound", false);
            return result;
        }
        TableSession session = tableService.currentSession(tableContext.getTableId()).orElse(null);
        if (session == null) {
            result.put("bound", true);
            result.put("open", false);
            return result;
        }

        result.put("bound", true);
        result.put("open", true);
        result.put("totalAmount", session.getTotalAmount());
        result.put("subtotalAmount", session.getSubtotalAmount());
        result.put("guestCount", session.getGuestCount());
        result.put("orders", session.getBillableOrders().stream()
                .map(o -> Map.of(
                        "orderNumber", o.getOrderNumber(),
                        "status", o.getStatus().name(),
                        "statusLabel", o.getStatus().getCustomerLabel()))
                .toList());
        return result;
    }

    /** 伝票のうち、まだ提供されていない注文があるか（画面のバッジ用）。 */
    @ModelAttribute("hasPending")
    public boolean hasPending() {
        if (!tableContext.isBound()) {
            return false;
        }
        return tableService.currentSession(tableContext.getTableId())
                .map(TableSession::hasPendingOrders)
                .orElse(false);
    }

    /** 状態の並び順（進捗表示に使う）。 */
    @ModelAttribute("statusOrder")
    public List<jp.komeko.order.domain.OrderStatus> statusOrder() {
        return List.of(
                jp.komeko.order.domain.OrderStatus.RECEIVED,
                jp.komeko.order.domain.OrderStatus.COOKING,
                jp.komeko.order.domain.OrderStatus.READY,
                jp.komeko.order.domain.OrderStatus.COMPLETED);
    }
}
