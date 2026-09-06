package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.Order;
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
            if (!placed.soldOutNotices().isEmpty()) {
                redirectAttributes.addFlashAttribute("flashErrors", placed.soldOutNotices());
            }
            // 「承りました」は画面そのものが言うので、フラッシュには書かない。
            // 両方に出すと同じ文が 2 回並ぶ。
            //
            // 遷移先を伝票から専用の画面に変えた（設計 暗07 / 2026-09-06）。
            // 伝票へ飛ばすと、いま頼んだぶんがこれまでの注文に紛れる。
            // 3 杯目のビールを頼んだのに 3 行ビールが並ぶ画面では、
            // 頼み間違いにその場で気づけない。
            return "redirect:/ordered/" + placed.order().getPublicToken();
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
            // 会計が済んだ直後など。
            //
            // ★ ここで「ありがとうございました」だけを出して終わりにしない。
            //   締めた瞬間に、お客さまの手元から金額も明細も消えます。
            //   割り勘の計算も、経費で落とすときの控えも取れません。
            //   自分がついていた伝票を読み直して、お会計の内容を残します。
            //
            //   ★ 卓から引き直してはいけません。「その卓の最後に締まった伝票」だと、
            //     同じ席の次の組が会計を済ませたとき、前の組のスマホに
            //     次の組の伝票が出ます（TableContext#sessionId のコメント参照）。
            model.addAttribute("tableName", tableContext.getTableName());
            model.addAttribute("closedBill", closedBillOfThisBrowser());
            return "customer/bill-closed";
        }

        // 会計後に読み直せるよう、ついている伝票を覚えておく。
        // 入店時にも控えているが、セッションが作り直された場合はここが最後の砦になる
        tableContext.rememberSession(session.getId());

        model.addAttribute("bill", session);
        model.addAttribute("orders", session.getBillableOrders());
        return "customer/bill";
    }

    /**
     * このブラウザがついていた伝票（会計済み）。無ければ null。
     *
     * <p>覚えていない（一度も伝票につかないまま会計された）ときや、
     * 何かの拍子にまだ開いているときは null を返します。
     * 画面はそのとき、金額のない「ありがとうございました」だけを出します。
     * <b>分からないものを、それらしく埋めないこと。</b>
     */
    private TableSession closedBillOfThisBrowser() {
        Long id = tableContext.getSessionId();
        if (id == null) {
            return null;
        }
        try {
            // getSession は注文まで読み終えてから返す（open-in-view: false なので、
            // 画面を描く時点では DB 接続が無い）
            TableSession bill = tableService.getSession(id);
            return bill.isClosed() ? bill : null;
        } catch (RuntimeException e) {
            // 伝票が消えている（デモの入れ直しなど）。金額なしの画面に落とす
            return null;
        }
    }

    /**
     * ご注文を承りました（設計 暗07）。いま通った注文<b>だけ</b>を出す。
     *
     * <p><b>なぜ伝票ではなく専用の画面なのか</b><br>
     * 以前は注文のあと伝票へ飛ばしていましたが、そこには
     * これまでのご注文が全部並びます。3 杯目のビールを頼んだ人の画面には
     * ビールが 3 行。<b>どれがいま頼んだぶんか分かりません。</b>
     * 頼み間違いに気づけるのは注文した直後がいちばん早いので、
     * その一瞬だけを見せる画面を分けました。
     *
     * <p><b>連番の ID ではなくトークンで指すこと。</b>
     * 番号を 1 つずらすだけで他の卓の注文が読めてしまいます
     * （キャンセルの口が {@code publicToken} を使っているのと同じ理由）。
     *
     * <p>再読み込みしても同じ画面が出ます（PRG）。
     * 注文が見つからないときは伝票へ戻します。トークンが古いだけで、
     * お客さまにとっては「さっき頼んだもの」が伝票にあるはずだからです。
     */
    @GetMapping("/ordered/{token}")
    public String ordered(@PathVariable String token, Model model) {
        Order order = orderService.findByToken(token).orElse(null);
        if (order == null) {
            return "redirect:/bill";
        }
        model.addAttribute("order", order);
        return "customer/order-placed";
    }

    /*
     * ★ ここにあった POST /bill/orders/{token}/cancel を外しました（2026-09-06）。
     *
     * 伝票の画面に出していた「ご注文 #119 をキャンセル」を外したのに合わせています。
     * 注文番号はその日の店全体の通し番号で、明細のどこにも出ていないため、
     * その番号がどの品なのかお客さまには分かりませんでした。
     *
     * ★ ボタンだけ消して口を残さないこと。
     *   この URL は認証なし（SecurityConfig で permitAll）なので、
     *   画面から消しても叩けます。同じ操作に入口が 2 つある状態にすると、
     *   片方に掛けた判断がもう片方から素通りします。
     *   厨房の changeStatus が CANCELED を受け付けない理由と同じです。
     *
     * 取り消しはスタッフが行います（ホール／厨房の画面 → OrderService#cancelByStaff）。
     * お客さまはサービスのタブからスタッフを呼べます。
     */

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
