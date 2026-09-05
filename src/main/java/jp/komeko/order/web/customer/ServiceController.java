package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.ServiceCallType;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.MenuService;
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
 * <p><b>持っていく物は、商品の注文とまったく同じ道を通ります。</b>
 * タイルを押すと注文リスト（カート）に入るだけで、まだ送られません。
 * 「注文へ進む」で確定して、はじめて厨房に飛びます。
 *
 * <p>はじめは 1 回押しただけで送っていましたが、店主から
 * 「確定させるまで送信しましたと出したくない。商品注文と同じ処理にしてほしい」。
 * もっともで、押した瞬間に送る口がここだけ違うと、
 * <b>お客さまは「押したら出てくるのか、まだなのか」を画面ごとに覚え直す</b>ことになります。
 * 同じ形をしたものは同じように動くほうが、説明の要らない画面になります。
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
     * タイルに出すアイコン。商品名 → 画像のファイル名。
     *
     * <p>設計ではこの 4 つが<b>画像</b>で置かれていて、ベクタがありません。
     * 似せて描き直したものを一度入れましたが、手で描いた形はどうやっても別物になり、
     * 「アイコンがフィグマデータと違う」と指摘されました。
     * Figma の画像をそのまま {@code /images/icons/} に置いて使っています。
     *
     * <p>ここに無い商品は、下の {@link #TILE_SVG_ICONS} を見ます。
     * どちらにも無ければ札だけのタイルになります。落ちません。
     * アイコンは飾りなので、商品名を変えたくらいで画面が壊れないようにしてあります。
     */
    private static final Map<String, String> TILE_IMAGE_ICONS = Map.of(
            "お水", "water.png",
            "取り皿", "plate.png",
            "灰皿", "ashtray.png",
            "塩コショウ", "shaker.png");

    /**
     * ベクタで持っているアイコン。商品名 → {@code fragments/icons.html} の名前。
     *
     * <p>こちらは色が文字色に追従します（{@code fill="currentColor"}）。
     */
    private static final Map<String, String> TILE_SVG_ICONS = Map.of(
            "おしぼり", "ic_towel",
            "領収書", "ic_receipt");

    private final MenuService menuService;
    private final TableService tableService;
    private final TableContext tableContext;

    /**
     * そのお客さまの注文リスト。
     *
     * <p>{@code @SessionScope} なので、シングルトンとして受け取っても
     * 実際に触った瞬間に「その人のカート」へ転送されます（{@code Cart} のコメント）。
     * ここで {@code new Cart()} を作ると、入れたそばから捨てられる別物になります。
     */
    private final Cart cart;
    private final CartService cartService;
    private final ServiceCallService serviceCallService;

    public ServiceController(MenuService menuService,
                             TableService tableService,
                             TableContext tableContext,
                             Cart cart,
                             CartService cartService,
                             ServiceCallService serviceCallService) {
        this.menuService = menuService;
        this.tableService = tableService;
        this.tableContext = tableContext;
        this.cart = cart;
        this.cartService = cartService;
        this.serviceCallService = serviceCallService;
    }

    @GetMapping
    public String service(Model model) {
        if (!tableContext.isBound()) {
            return "customer/no-table";
        }
        model.addAttribute("items", serviceItems());
        model.addAttribute("imageIcons", TILE_IMAGE_ICONS);
        model.addAttribute("svgIcons", TILE_SVG_ICONS);
        model.addAttribute("calls", List.of(ServiceCallType.values()));
        return "customer/service";
    }

    /**
     * 物を 1 つ、注文リストに入れる。
     *
     * <p><b>ここでは送りません。</b>商品と同じく、確定するのは「注文へ進む」のあとです。
     * 文言も商品のときとそろえてあります。
     * 同じ言葉で同じことが起きる、が画面をまたいで守られていないと、
     * お客さまは画面ごとに覚え直すことになります。
     *
     * <p>押したあとは、このサービスの画面に戻します
     * （商品は {@code /cart/add} がメニューへ戻す）。
     * 取り皿とおしぼりを続けて頼むのはよくあることなので、
     * そのたびにメニューへ連れて行かれると戻る操作が要ります。
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
            cartService.addToCart(cart, item.getId(), List.of(), 1);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "注文リストに追加しました（まだ注文は確定していません）");
        } catch (RuntimeException e) {
            log.warn("サービスのご依頼を注文リストに入れられませんでした: {}", e.toString());
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
