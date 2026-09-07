package jp.komeko.order.web.staff;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.TableService;
import jp.komeko.order.web.customer.CartController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 店舗の端末（スマホ・iPad）から注文を受ける画面（設計「店舗版スマホ注文」）。
 *
 * <p><b>お客さまの QR 注文と同じ仕組みで、入口だけ変えたものです。</b>
 *
 * <pre>
 * お客さま版:  QR を読む（＝卓が決まる）→ メニュー → カート → まとめて送る
 * 店舗版:      番号を押す（＝卓が決まる）→ メニュー → カート → まとめて送る
 * </pre>
 *
 * <p>メニュー・品選び・カート・売り切れの守り・選択肢の検証・厨房への流れは
 * <b>お客さま側のものをそのまま使います</b>。このコントローラが持つのは
 * 「番号の盤面」と「送信の口」だけです。
 *
 * <p><b>なぜ卓の入れ物（{@link TableContext}）を共有するのか</b><br>
 * 店員用に別の入れ物を作ると、メニュー・品選び・カートの 3 画面が
 * 「どちらを見るか」を自分で判断することになります。
 * 判断の場所が増えるほど、片方だけ直したときのずれが起きます。
 * 卓に着いていることは同じなので入れ物も同じにして、
 * 違うところだけ {@code staffMode} という印で分けています。
 */
@Controller
@RequestMapping("/staff/order")
public class StaffOrderController {

    private static final Logger log = LoggerFactory.getLogger(StaffOrderController.class);

    /** 盤面に出す時刻の書式（19:20 のような形）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 人数の選択肢。ここを超える組は、ホール画面から人数を直す。 */
    private static final int MAX_GUEST_CHOICE = 8;

    private final TableService tableService;
    private final CartService cartService;
    private final OrderService orderService;
    private final Cart cart;
    private final TableContext tableContext;

    public StaffOrderController(TableService tableService,
                                CartService cartService,
                                OrderService orderService,
                                Cart cart,
                                TableContext tableContext) {
        this.tableService = tableService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.cart = cart;
        this.tableContext = tableContext;
    }

    // ========================================================================
    //  番号の盤面
    // ========================================================================

    /**
     * 盤面の 1 マス。
     *
     * @param table    卓（＝番号）
     * @param bill     開いている伝票。空席なら null
     * @param openedAt 入店時刻の表示（空席なら null）
     */
    public record Seat(DiningTable table, TableSession bill, String openedAt) {

        /** 使用中か（伝票が開いている）。 */
        public boolean inUse() {
            return bill != null;
        }

        /** 人数。空席なら 0。 */
        public int guestCount() {
            return bill == null ? 0 : bill.getGuestCount();
        }

        /** 現在の金額。空席なら 0。 */
        public int amount() {
            return bill == null ? 0 : bill.getTotalAmount();
        }

        /**
         * お会計待ちか（卓 3/3。2026-09-07）。
         *
         * <p>お客さまがスマホから会計を頼むと伝票が CLOSING になるが、
         * それはホール画面にしか出ていなかった。番号盤面しか見ていない
         * 店員は気づけず、お客さまが待ち続ける穴があった。
         * ここに印を出す。<b>印だけ</b>で、会計の操作はホール画面の仕事。
         */
        public boolean closing() {
            return bill != null && bill.isClosing();
        }
    }

    /**
     * 番号の盤面。
     *
     * <p><b>空席も出します。</b>着席したお客さまから最初の注文を受ける場面で、
     * ここに出ていないと「ご案内」のためにホール画面へ行くことになります。
     * 押したら人数を聞いて、そのまま品選びへ進みます。
     *
     * <p><b>人数・入店時刻・金額を添えるのはカウンター対策です。</b>
     * 札は組の前に置いてあるので、店員は目の前の札を読んで押すだけですが、
     * 厨房など離れた場所から入れるときは、番号だけでは組を見分けられません。
     */
    @GetMapping
    public String board(Model model) {
        List<Seat> seats = new ArrayList<>();
        for (DiningTable table : tableService.activeTables()) {
            Optional<TableSession> bill = tableService.currentSession(table.getId());
            seats.add(new Seat(table, bill.orElse(null),
                    bill.map(b -> b.getOpenedAt().format(TIME_FORMAT)).orElse(null)));
        }
        model.addAttribute("seats", seats);

        // 送っていない品を抱えたまま盤面に戻ってきた場合、それを画面に出す。
        // 出さないと、次の卓を押したときに初めて気づくことになる
        if (tableContext.isBound() && !cart.isEmpty()) {
            model.addAttribute("pendingTableName", tableContext.getTableName());
            model.addAttribute("pendingCount", cart.getTotalQuantity());
        }
        return "staff/order-board";
    }

    /**
     * 番号を選ぶ。
     *
     * <p><b>送っていない品を持ったまま別の番号を押したら止めます。</b>
     * 黙って持ち越すと、札2 に積んだビールが札5 の伝票に化けます。
     * 同じ番号を押し直したときは、続きなので止めません。
     *
     * <p>伝票が開いていなければ人数を聞く画面へ、開いていればメニューへ進みます。
     */
    @GetMapping("/seats/{tableId}")
    public String selectSeat(@PathVariable Long tableId,
                             @RequestParam(required = false) boolean discard,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        DiningTable table;
        try {
            table = tableService.getById(tableId);
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/staff/order";
        }

        // 別の卓の品を持ったままなら、捨てるか送るかを決めてもらう
        boolean movingAway = tableContext.isBound()
                && !tableId.equals(tableContext.getTableId())
                && !cart.isEmpty();
        if (movingAway && !discard) {
            model.addAttribute("from", tableContext.getTableName());
            model.addAttribute("fromTableId", tableContext.getTableId());
            model.addAttribute("to", table);
            model.addAttribute("count", cart.getTotalQuantity());
            model.addAttribute("amount", cart.getTotalAmount());
            return "staff/order-switch";
        }
        if (movingAway) {
            log.info("店舗版: {} の未送信 {} 点を破棄して {} へ移動",
                    tableContext.getTableName(), cart.getTotalQuantity(), table.getName());
            cart.clear();
        }

        tableContext.bindByStaff(table.getId(), table.getName());

        Optional<TableSession> bill = tableService.currentSession(table.getId());
        if (bill.isEmpty()) {
            // 空席。人数を聞いてから伝票を開く（ご案内と最初の注文が 1 動線になる）
            model.addAttribute("table", table);
            model.addAttribute("guestOptions", guestOptions());
            return "staff/order-guests";
        }
        tableContext.rememberSession(bill.get().getId());
        return "redirect:/menu";
    }

    /**
     * 空席にご案内して、そのまま注文へ進む。
     *
     * <p>人数はテーブルチャージ（単価 × 人数）の計算に要るので飛ばせません。
     * 伝票を開く判断は {@link TableService#openSession} が持っています。
     */
    @PostMapping("/seats/{tableId}/open")
    public String openSeat(@PathVariable Long tableId,
                           @RequestParam(defaultValue = "2") int guestCount,
                           RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.openSession(tableId, guestCount);
            tableContext.bindByStaff(bill.getDiningTable().getId(), bill.getDiningTable().getName());
            tableContext.rememberSession(bill.getId());
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s に %d 名さまをご案内しました".formatted(
                            bill.getDiningTable().getName(), bill.getGuestCount()));
            return "redirect:/menu";

        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/staff/order";
        }
    }

    // ========================================================================
    //  積む・送る
    // ========================================================================

    /**
     * 品をカートに積む（店員用）。
     *
     * <p>お客さま用の {@code /cart/add} と分けているのは、
     * <b>時価の金額を受け取る</b>ためです。
     * 判断は {@code CartService#addByStaff} が持っています
     * （金額を入れるまで積めない、という門）。
     */
    @PostMapping("/cart/add")
    public String addToCart(@RequestParam Long menuItemId,
                            @RequestParam(name = "choiceIds", required = false) List<Long> choiceIds,
                            @RequestParam(defaultValue = "1") int quantity,
                            @RequestParam(required = false) Integer price,
                            @RequestParam Map<String, String> allParams,
                            RedirectAttributes redirectAttributes) {
        if (!tableContext.isStaffMode()) {
            return "redirect:/staff/order";
        }
        // ラジオの選択肢はお客さま側とまったく同じ規則でまとめる。
        // 規則そのものは CartController が持っている
        List<Long> selected = CartController.mergeChoiceIds(choiceIds, allParams);
        try {
            cartService.addByStaff(cart, menuItemId, selected, quantity, price);
            redirectAttributes.addFlashAttribute("flashSuccess", CartController.ADDED_TO_CART_MESSAGE);
        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
            return "redirect:/items/" + menuItemId;
        }
        return "redirect:/menu";
    }

    /**
     * カートの中身をまとめて注文にする。
     *
     * <p>お客さまの注文と同じ処理を通ります（{@code OrderService#place}）。
     * 違うのは<b>入力者が記録される</b>ことだけです。
     *
     * <p>送ったあとは盤面に戻します。次の卓へ移る場面が続くためです。
     */
    @PostMapping("/submit")
    public String submit(@RequestParam(required = false) String note,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirectAttributes) {
        if (!tableContext.isStaffMode() || tableContext.getSessionId() == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("先に番号を選んでください"));
            return "redirect:/staff/order";
        }
        String tableName = tableContext.getTableName();
        try {
            OrderService.Placed placed = orderService.placeFromStaffCart(
                    cart, tableContext.getSessionId(), note, staffNameOf(user));
            Order order = placed.order();
            cart.clear();

            List<String> notices = new ArrayList<>(placed.soldOutNotices());
            notices.add("%s に %d 点を送りました（#%d・¥%,d）"
                    .formatted(tableName, order.getTotalQuantity(),
                            order.getOrderNumber(), order.getTotalAmount()));
            redirectAttributes.addFlashAttribute("flashSuccess", String.join("\n", notices));

        } catch (OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());
            return "redirect:/cart";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/cart";
        }
        return "redirect:/staff/order";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    private static List<Integer> guestOptions() {
        List<Integer> options = new ArrayList<>();
        for (int i = 1; i <= MAX_GUEST_CHOICE; i++) {
            options.add(i);
        }
        return options;
    }

    private static String staffNameOf(StaffUserDetails user) {
        if (user == null) {
            return "スタッフ";
        }
        String displayName = user.getDisplayName();
        return (displayName == null || displayName.isBlank()) ? user.getUsername() : displayName;
    }

    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? "その操作は行えませんでした" : message;
    }
}
