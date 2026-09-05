package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.ServiceCallType;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ServiceCallService;
import jp.komeko.order.service.TableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * サービスの画面（設計「暗03 サービス」）。
 *
 * <p>タイルを 1 回押すだけで用が済む画面です。カートには入りません。
 * 「お水を 1 つカートに入れて、注文へ進んで、確定する」では、
 * 手を挙げてスタッフを呼ぶより手間がかかってしまいます。
 *
 * <p><b>タイルは 2 種類あり、通り道が違います。</b>（2026-09-05 に店主と決めた）
 * <table>
 *   <caption>振り分け</caption>
 *   <tr><th>種類</th><th>例</th><th>通り道</th><th>出る場所</th></tr>
 *   <tr><td>持っていく物</td><td>お水・おしぼり・取り皿・灰皿・塩コショウ・領収書</td>
 *       <td>¥0 の商品として注文に流す</td><td>厨房ボード</td></tr>
 *   <tr><td>呼びかけ</td><td>スタッフを呼ぶ・お会計をお願いする</td>
 *       <td>{@link ServiceCallService}</td><td>ホール画面</td></tr>
 * </table>
 *
 * <p>物のほうを注文に乗せているのは、そこに「出したか」という<b>進行</b>があるからです。
 * 厨房ボードの状態遷移（受付 → 調理中 → 提供済）がそのまま使えます。
 * 呼びかけのほうには進行が 2 つ（呼ばれた／対応した）しか無く、
 * 金額も個数も無いので、注文に混ぜると売上と客単価の意味が濁ります。
 */
@Controller
@RequestMapping("/service")
public class ServiceController {

    private static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    /**
     * 持っていく物が入っているカテゴリの名前。
     *
     * <p>この名前のカテゴリに入れた ¥0 の商品が、そのままタイルになります。
     * 品ぞろえを変えたい店長は、管理画面から商品を足すだけで済みます。
     * コードに品名を書き込むと、灰皿を置かない店でも灰皿が出てしまいます。
     */
    public static final String SERVICE_CATEGORY = "サービス";

    /**
     * タイルに出すアイコン。商品名 → {@code fragments/icons.html} の名前。
     *
     * <p>ここに無い商品は札だけのタイルになります。落ちません。
     * アイコンは飾りなので、名前が変わったくらいで画面が壊れないようにしてあります。
     */
    private static final Map<String, String> TILE_ICONS = Map.of(
            "お水", "ic_water",
            "おしぼり", "ic_towel",
            "取り皿", "ic_plate",
            "灰皿", "ic_ashtray",
            "塩コショウ", "ic_shaker",
            "領収書", "ic_receipt");

    private final MenuService menuService;
    private final TableService tableService;
    private final TableContext tableContext;
    private final CartService cartService;
    private final OrderService orderService;
    private final ServiceCallService serviceCallService;

    public ServiceController(MenuService menuService,
                             TableService tableService,
                             TableContext tableContext,
                             CartService cartService,
                             OrderService orderService,
                             ServiceCallService serviceCallService) {
        this.menuService = menuService;
        this.tableService = tableService;
        this.tableContext = tableContext;
        this.cartService = cartService;
        this.orderService = orderService;
        this.serviceCallService = serviceCallService;
    }

    @GetMapping
    public String service(Model model) {
        if (!tableContext.isBound()) {
            return "customer/no-table";
        }
        model.addAttribute("items", serviceItems());
        model.addAttribute("icons", TILE_ICONS);
        model.addAttribute("calls", List.of(ServiceCallType.values()));
        return "customer/service";
    }

    /**
     * 物を 1 つ頼む。¥0 の注文として厨房ボードへ飛ぶ。
     *
     * <p>本番と同じ道（カートに入れて注文する）を通します。
     * ここだけ直接 INSERT すると、伝票の再計算も在庫の引き当ても通らず、
     * 「画面では動いたのに実際は違う」がいちばん起きやすい形になります。
     */
    @PostMapping("/items/{id}")
    public String request(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (!tableContext.isBound()) {
            return "redirect:/";
        }
        MenuItem item = serviceItems().stream()
                .filter(i -> i.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (item == null) {
            // URL を直に叩かれても、サービス以外の品をここから ¥0 で頼めないようにする
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("そのご依頼はお受けできません。お手数ですがスタッフへお声がけください。"));
            return "redirect:/service";
        }

        try {
            TableSession session = tableService.requireOpenSession(tableContext.getTableId());
            Cart cart = new Cart();
            cartService.addToCart(cart, item.getId(), List.of(), 1);
            orderService.place(cart, session.getId(), null);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    item.getName() + " を承りました。少々お待ちください。");
        } catch (RuntimeException e) {
            log.warn("サービスのご依頼を受け付けられませんでした: {}", e.toString());
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("ただいまお受けできませんでした。スタッフへお声がけください。"));
        }
        return "redirect:/service";
    }

    /**
     * スタッフを呼ぶ／お会計をお願いする。
     *
     * <p>伝票の画面からも同じ口を使います（「お会計をお願いする」ボタン）。
     */
    @PostMapping("/call/{type}")
    public String call(@PathVariable ServiceCallType type,
                       @org.springframework.web.bind.annotation.RequestParam(required = false) String from,
                       RedirectAttributes redirectAttributes) {
        if (!tableContext.isBound()) {
            return "redirect:/";
        }
        try {
            TableSession session = tableService.requireOpenSession(tableContext.getTableId());
            serviceCallService.call(session.getId(), type);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "承りました。スタッフがお席へ伺います。");
        } catch (RuntimeException e) {
            log.warn("呼び出しを受け付けられませんでした: {}", e.toString());
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("ただいまお受けできませんでした。スタッフへお声がけください。"));
        }
        // 押したページへ戻す。伝票から押した人を、サービスの画面へ飛ばさない
        return "bill".equals(from) ? "redirect:/bill" : "redirect:/service";
    }

    /**
     * サービスのカテゴリにある、注文できる品。
     *
     * <p>売り切れ（＝いま切らしている灰皿など）は落とします。
     * 押しても出てこないタイルは、押せるように見えるだけ不親切です。
     */
    private List<MenuItem> serviceItems() {
        for (Map.Entry<Category, List<MenuItem>> entry : menuService.customerMenu().entrySet()) {
            if (SERVICE_CATEGORY.equals(entry.getKey().getName())) {
                return entry.getValue().stream().filter(MenuItem::isOrderable).toList();
            }
        }
        return List.of();
    }
}
