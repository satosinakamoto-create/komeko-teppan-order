package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.SettlementMethod;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.OrderRejectedException;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ServiceCallService;
import jp.komeko.order.service.ShopSettingService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ホール（フロア）担当者が使う「伝票一覧」と「お会計」の画面。
 *
 * <p><b>この画面がやること</b>
 * <ol>
 *   <li>いま何卓が入っていて、それぞれいくらになっているかを一覧で見せる</li>
 *   <li>空いている卓に「ご案内」して伝票を開く</li>
 *   <li>人数を直す（テーブルチャージが変わる）</li>
 *   <li><b>お会計＝伝票を締める</b>。この店の売上が確定する、いちばん大事な操作</li>
 *   <li>誤って会計してしまったときに取り消す（リカバリ）</li>
 * </ol>
 *
 * <p><b>金額は絶対にここで計算しない</b><br>
 * 小計・テーブルチャージ・深夜料金・ご請求額・内消費税の計算は
 * {@link TableSession#recalculate(LocalDateTime, boolean)} の 1 箇所だけにあります。
 * コントローラや画面で「小計＋チャージ」のような式を書くと、
 * 片方だけ直したときに<b>お客さまに請求する金額とレジの金額がズレます</b>。
 * ここでは {@code getTotalAmount()} などの getter を読むだけにしています。
 *
 * <p><b>コントローラの役割</b><br>
 * 「リクエストを受ける → サービスに仕事を頼む → 画面に渡す値を用意する」だけの
 * 交通整理係です。業務のルールは {@link TableService} 側に置いてあります。
 *
 * <p><b>PRG（Post → Redirect → Get）</b><br>
 * 更新系（POST）のあとは必ずリダイレクトします。そうしないと、
 * 処理後の画面でブラウザの再読み込みを押したときに同じ POST が再送され、
 * <b>二重に会計してしまう</b>おそれがあります。会計画面では特に致命的です。
 */
@Controller
@RequestMapping("/hall")
public class HallController {

    private static final Logger log = LoggerFactory.getLogger(HallController.class);

    /** 画面に出す時刻の書式（19:02 のような形）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 人数プルダウンに並べる最大値。実店舗の座敷でも足りる程度に取っておく。 */
    private static final int MAX_GUEST_CHOICE = 20;

    /**
     * ご案内フォームの人数の初期値。
     *
     * <p>卓の席数（capacity）を初期値にすると、2 名で 4 人掛けに座ったときに
     * テーブルチャージを 2 名分多く頂いてしまいます。
     * 金額は「多すぎる」より「少なすぎる」ほうがまだ取り返しがつくので、
     * もっとも多い組数である 2 名を初期値にしています。
     * 間違えてもすぐ気づけるよう、ご案内後のメッセージに人数を出しています。
     */
    private static final int DEFAULT_GUEST_COUNT = 2;

    /** 会計メモの最大文字数（{@code TableSession.note} の桁数に合わせる）。 */
    private static final int NOTE_MAX_LENGTH = 200;

    private final TableService tableService;
    private final ShopSettingService shopSettingService;
    private final OrderService orderService;

    /**
     * スタッフが卓に代わって注文を入れる画面で、商品と選択肢を引くために使う。
     *
     * <p>注文を通す判断（売り切れ・残数・金額）は {@link OrderService} 側にあります。
     * ここで読むのは<b>画面に並べるため</b>だけです。
     */
    private final MenuService menuService;

    /**
     * お客さまからの呼び出し。
     *
     * <p>ホールが受け持つのは「人が向かう」用件だけです。
     * お水やおしぼりのような<b>持っていく物</b>は ¥0 の注文として
     * 厨房ボードに出るので、ここには来ません。
     */
    private final ServiceCallService serviceCallService;

    /**
     * コンストラクタインジェクション。
     *
     * <p>Spring が「このクラスを作るにはこれらが要る」と判断して自動で渡してくれます。
     * フィールドに {@code @Autowired} を付ける書き方より、
     * final にできてテストもしやすいので、こちらが推奨です。
     */
    public HallController(TableService tableService,
                          ShopSettingService shopSettingService,
                          OrderService orderService,
                          ServiceCallService serviceCallService,
                          MenuService menuService) {
        this.tableService = tableService;
        this.shopSettingService = shopSettingService;
        this.orderService = orderService;
        this.serviceCallService = serviceCallService;
        this.menuService = menuService;
    }

    // ========================================================================
    //  伝票一覧（ホールのホーム画面）
    // ========================================================================

    /**
     * 伝票一覧。
     *
     * <p>「いま開いている伝票」と「まだ誰も座っていない卓」を並べます。
     * 空席の判定は、<b>開いている伝票が指している卓の ID を集めて、
     * そこに入っていない卓が空席</b>という考え方です。
     * 卓側に「使用中フラグ」を持たせると、会計の取り消しなどで
     * フラグと伝票の状態がズレたときに直しようがなくなるため、
     * 常に伝票のほうから導き出しています（＝事実は 1 箇所にだけ持つ）。
     */
    @GetMapping
    public String board(Model model) {
        // ★ 明細まで読む。盤面のお会計モーダルに「何を頼んだか」を出すため。
        //   openSessions() のままだと、テンプレートで order.lines を書いた瞬間に
        //   LazyInitializationException で画面ごと落ちる（open-in-view: false）
        List<TableSession> bills = tableService.openSessionsWithLines();
        List<DiningTable> tables = tableService.activeTables();

        // 開いている伝票が使っている卓の ID を集める。
        // Set（集合）にすると contains の判定が速く、重複も自動で消えます。
        Set<Long> occupiedTableIds = new HashSet<>();
        for (TableSession bill : bills) {
            occupiedTableIds.add(bill.getDiningTable().getId());
        }

        // ── 3 列に分ける（2026-09-12。設計 540:3509／540:3496／453:5744）──
        //
        // 厨房ボードの「受付／調理中／提供待ち」と同じ考え方で、
        // 列そのものを<b>いま何をすべき卓か</b>にする。
        // 以前は在席の伝票が 1 つの枠に混ざっていたので、
        // お会計待ちの卓を見つけるのにカードの札を 1 枚ずつ読む必要があった。
        List<TableSession> seatedBills = new ArrayList<>();
        List<TableSession> closingBills = new ArrayList<>();
        for (TableSession bill : bills) {
            if (bill.isClosing()) {
                closingBills.add(bill);
            } else {
                seatedBills.add(bill);
            }
        }

        // 伝票の無い卓を「片付け待ち」と「空席」に分ける（4 状態目。2026-09-07）。
        // needsCleanup は伝票からは導けない事実（卓の上が片付いたか）だけを受け持つ。
        //
        // 空席は列にしない。ご案内の入口は「＋新規お客様」に一本化してあり
        //（2026-09-11）、盤面には数だけ出す
        List<DiningTable> cleanupTables = new ArrayList<>();
        List<DiningTable> vacantTables = new ArrayList<>();
        for (DiningTable table : tables) {
            if (occupiedTableIds.contains(table.getId())) {
                continue;
            }
            if (table.isNeedsCleanup()) {
                cleanupTables.add(table);
            } else {
                vacantTables.add(table);
            }
        }

        // 在席のお客さまの合計人数と、まだ厨房に残っている注文の件数。
        // 件数の数え方は TableSession#hasPendingOrders() と同じ条件（受付・調理中）に
        // そろえてあります。片方だけ READY を含めると、カードのバッジと
        // 上の集計が食い違って「どっちが本当？」となるためです。
        int guestTotal = 0;
        int pendingCount = 0;
        for (TableSession bill : bills) {
            guestTotal += bill.getGuestCount();
            for (Order order : bill.getOrders()) {
                if (order.getStatus().isActive()) {
                    pendingCount++;
                }
            }
        }

        model.addAttribute("bills", bills);
        // 列ごとの中身と件数。
        //
        // ★ エリアごとの区切り（卓 2/3。2026-09-07）は、この画面から外した。
        //   列が「状態」になったので、その中をさらに「エリア」で割ると
        //   細い 1 列の中に見出しが二重に積み上がって、かえって探しにくい。
        //   エリアの区切りはご案内の入力画面（/hall/seat/new）に残っている
        model.addAttribute("seatedBills", seatedBills);
        model.addAttribute("closingBills", closingBills);
        model.addAttribute("cleanupTables", cleanupTables);
        model.addAttribute("seatedCount", seatedBills.size());
        model.addAttribute("closingCount", closingBills.size());
        model.addAttribute("cleanupCount", cleanupTables.size());
        model.addAttribute("occupiedCount", bills.size());
        // 卓の一覧そのものは出さなくなった（2026-09-11。「＋新規お客様」へ移した）。
        // 数字だけ残すのは、まだ入れられるかを一目で見るため。
        // ここは「いますぐ通せる卓」なので、片付け待ちは数えない
        model.addAttribute("vacantCount", vacantTables.size());
        model.addAttribute("guestTotal", guestTotal);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("closedBills", closedBillsOfToday());

        // ── お会計モーダル用（2026-09-12）──
        // 深夜料金の初期チェックと「付けた場合の金額」は、伝票ページと同じ
        // checkoutViewOf を通す。ここで書き写すと計算が 2 か所になり、
        // 片方だけ直したときに「画面によって読み上げる金額が違う」事故になる
        Map<Long, CheckoutView> checkoutViews = new LinkedHashMap<>();
        for (TableSession bill : bills) {
            checkoutViews.put(bill.getId(), checkoutViewOf(bill));
        }
        model.addAttribute("checkoutViews", checkoutViews);

        // ── ご案内モーダル用 ──
        // 伝票の無い卓を全部。バッシング中も選べる（選べば片付け完了も同時に記録）
        List<DiningTable> seatTables = new ArrayList<>();
        for (DiningTable table : tables) {
            if (!occupiedTableIds.contains(table.getId())) {
                seatTables.add(table);
            }
        }
        model.addAttribute("seatTables", seatTables);
        model.addAttribute("guestOptions", guestOptions(MAX_GUEST_CHOICE));
        model.addAttribute("defaultGuestCount", DEFAULT_GUEST_COUNT);
        // お客さまからの呼び出し（スタッフを呼ぶ／お会計をお願いする）。
        // 持っていく物は厨房ボードに出るので、ここには来ない
        model.addAttribute("calls", serviceCallService.pending());
        // レイアウト（layout/staff.html）のナビで、いまいる場所に色を付けるための目印
        return "hall/board";
    }

    // ========================================================================
    //  伝票の詳細・お会計
    // ========================================================================

    /**
     * 伝票の詳細＋会計画面。
     *
     * <p><b>モデル名を "bill" にしている理由</b><br>
     * Thymeleaf では {@code ${session}} が HttpSession を指す予約語のため、
     * 伝票を "session" という名前でモデルに入れると衝突して正しく表示できません。
     * お客さま側の画面（{@code customer/bill.html}）と同じく "bill" に統一しています。
     */
    @GetMapping("/bills/{id}")
    public String bill(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        TableSession bill;
        try {
            bill = tableService.getSession(id);
        } catch (TableService.SessionNotFoundException e) {
            // 伝票が消えている（URL が古いなど）。エラーページに飛ばさず一覧に戻す。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }

        if (bill.isActive()) {
            // 深夜料金は時刻で決まるので、23 時をまたぐと金額が変わります。
            // 画面を開いた「いま」の金額を出すために計算し直します。
            //
            // ※ ここで受け取っている伝票は DB との接続が切れた状態（detached）なので、
            //    この計算結果が DB に書き戻されることはありません。表示専用です。
            //    実際に請求する金額は closeSession() の中でもう一度計算されるので、
            //    表示と請求がズレる心配はありません。
            tableService.refresh(bill);
        }

        // 画面が使う店舗設定（${shop}）は GlobalModelAttributes が入れてくれるので、
        // ここで取り出す必要はありません。
        // 以前は深夜料金チェックの初期値を shop.isLateNight(now) で決めていたため
        // ここで読んでいましたが、その判定をやめたので不要になりました。
        model.addAttribute("bill", bill);
        // キャンセル済みの注文は請求から外れるので、明細も billable のものだけ出す
        model.addAttribute("orders", bill.getBillableOrders());
        model.addAttribute("openedAtLabel", bill.getOpenedAt().format(TIME_FORMAT));
        model.addAttribute("closedAtLabel",
                bill.getClosedAt() == null ? null : bill.getClosedAt().format(TIME_FORMAT));
        // 深夜料金チェックボックスの初期状態。
        //
        // 「いま深夜帯か（shop.isLateNight(now)）」で決めてはいけません。
        // 深夜料金は注文時刻ごとに決まるので、
        //   ・23:30 に注文があった卓を、5:30（深夜帯の外）に会計する
        //   ・22:00 で注文が終わった卓を、23:30（深夜帯の中）に会計する
        // のどちらも起こります。前者はチェックが外れて取りっぱぐれ、
        // 後者は対象が無いのにチェックが入って紛らわしい、となります。
        //
        // 開いている伝票は表示のたびにルールで計算し直されているので、
        // その結果をそのまま初期状態にします。
        // ただし一度スタッフが免除した伝票は、開け直しても外れたままにします
        // （人の判断を、計算結果で上書きしない）。
        CheckoutView view = checkoutViewOf(bill);
        model.addAttribute("lateNightDefault", view.lateNightDefault());
        // 確認ダイアログ用の「深夜料金を付けた場合のご請求額」。
        //
        // ふつうは recalculate 済みの getTotalWithLateNight() でよいが、
        // 免除フラグが立った伝票は再計算が NONE に強制されるため
        // lateNightAmount が常に 0 で、「付けた場合」がどこにも計算されていない。
        // そのままだと、チェックを入れ直して締めるとき、ダイアログが
        // 割増抜きの金額を「深夜料金 込み」と読み上げてしまい、
        // 実際に締まった金額のほうが高くなる。
        //
        // ここで受け取っている伝票は detached（表示専用。上のコメント参照）なので、
        // 免除を一時的に外して計算しても DB には書き戻らない。
        model.addAttribute("totalIfLateNightApplied", view.totalIfLateNightApplied());
        // いまの人数が選択肢に無いと「変更したら人数が減った」という事故になるので、
        // 現在値より小さい範囲で切らないようにしておく
        model.addAttribute("guestOptions",
                guestOptions(Math.max(MAX_GUEST_CHOICE, bill.getGuestCount())));
        // チャージ除外は 0 名（＝除外なし）から、来店人数まで。
        // 人数を超える選択肢を出すと「6 名中 8 名を除外」が画面上は選べてしまい、
        // 保存時に黙って丸められる（＝押した数と違う結果になる）ので、ここで閉じる。
        model.addAttribute("exemptOptions", countOptions(bill.getGuestCount()));
        return "hall/bill";
    }

    // ========================================================================
    //  スタッフが卓に代わって注文を入れる
    // ========================================================================

    /**
     * 商品を選ぶ画面のカテゴリ 1 つぶん。
     *
     * <p>{@code Map<カテゴリ名, 商品>} にしないのは、同じ名前のカテゴリが 2 つあると
     * 別物どうしが 1 つの見出しに混ざるためです（厨房の品切れパネルと同じ理由）。
     *
     * @param name  見出しに出すカテゴリ名
     * @param items そのカテゴリの掲載中の商品（並び順は商品の sortOrder のまま）
     */
    public record PickCategory(String name, List<MenuItem> items) {
    }

    /**
     * スタッフが卓に代わって注文を入れる画面。
     *
     * <p><b>何のための画面か</b><br>
     * 時価の品（国産牛ステーキなど）は、その日の仕入れを見ないと金額が決まりません。
     * お客さまの画面には金額を出せないので「スタッフを呼ぶ」しか置いていません。
     * 呼ばれたスタッフが部位と焼き加減を聞き、その場で金額を伝える——
     * <b>その金額を注文として残す</b>のがこの画面です。
     *
     * <p><b>2 段階にしている理由</b><br>
     * 選択肢（焼き加減など）は商品ごとに違うので、商品が決まらないと出せません。
     * JavaScript で出し分けることもできますが、この画面は
     * <b>お客さまを待たせながら片手で操作する</b>場面で使います。
     * 通信が細ったときに選択肢だけ出てこない、という壊れ方をすると、
     * スタッフには「選ぶところが無い」ようにしか見えません。
     * 素の GET で組み立てておけば、表示されたものは必ず操作できます。
     *
     * <p>{@code itemId} が無ければ商品を選ぶ段、あれば内容を決める段です。
     */
    @GetMapping("/bills/{id}/orders/new")
    public String newOrder(@PathVariable Long id,
                           @RequestParam(required = false) Long itemId,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        TableSession bill;
        try {
            bill = tableService.getSession(id);
        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }

        // 入れられない伝票では、そもそも画面を開かせない。
        // 開かせてから送信時に断ると、選び終えたあとで捨てさせることになる
        if (!bill.isOrderable()) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(bill.isClosing()
                    ? "この伝票はお会計の準備中です。追加するには「注文を再開」してください"
                    : "この伝票はお会計が済んでいます。追加するには会計を取り消してください"));
            return "redirect:/hall/bills/" + id;
        }

        model.addAttribute("bill", bill);

        if (itemId == null) {
            // 時価の品を先頭に別枠で出す。この画面がある理由がそれだからです。
            // 掲載中の商品は 94 品・14 カテゴリあるので、カテゴリの並びに埋めると
            // 毎回スクロールして探すことになります。下の一覧にも同じ品が出ますが、
            // 一覧から消すと「時価の品はここにしか無い」という別の決まりが増えます。
            model.addAttribute("marketPricedItems", marketPricedItems());
            model.addAttribute("categoryGroups", pickCategories());
            return "hall/order-new";
        }

        MenuItem item;
        try {
            item = menuService.itemWithOptions(itemId);
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall/bills/" + id + "/orders/new";
        }
        if (!item.isVisible()) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("「%s」は掲載を終了しています".formatted(item.getName())));
            return "redirect:/hall/bills/" + id + "/orders/new";
        }

        model.addAttribute("item", item);
        // 時価かどうかで、金額の欄を出すか・売り切れを警告として扱うかが変わる。
        // 判定（価格 0 以下）を画面に書くと、意味が変わったときに直し漏れるので Java 側で持つ
        model.addAttribute("marketPriced", item.getPrice() <= 0);
        return "hall/order-new";
    }

    /**
     * スタッフが入れた注文を確定する。
     *
     * <p>判断は {@link OrderService#placeByStaff} が持っています。
     * ここは受け取って渡し、結果を言葉にするだけです。
     *
     * <p>断られたときは<b>選んだ商品の段に戻します</b>。
     * 商品を選ぶ段まで戻すと、金額の打ち直しのために
     * もう一度カテゴリから辿り直すことになります。
     */
    @PostMapping("/bills/{id}/orders")
    public String addOrder(@PathVariable Long id,
                           @RequestParam Long itemId,
                           @RequestParam(name = "choiceIds", required = false) List<Long> choiceIds,
                           @RequestParam(defaultValue = "1") int quantity,
                           @RequestParam(required = false) Integer price,
                           @RequestParam(required = false) String note,
                           @RequestParam Map<String, String> allParams,
                           @AuthenticationPrincipal StaffUserDetails user,
                           RedirectAttributes redirectAttributes) {
        // 1 つだけ選ぶ選択肢（焼き加減など）はラジオで届くので、
        // お客さま側とまったく同じ規則でまとめ直す。
        // 規則そのものは CartController が持っている（片方だけ直すと選択肢が黙って落ちる）
        List<Long> selected = CartController.mergeChoiceIds(choiceIds, allParams);
        try {
            Order order = orderService.placeByStaff(
                    id, itemId, selected, quantity, price, note, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s に「%s」を入れました（#%d・¥%,d）。厨房に出ています"
                            .formatted(order.getCustomerName(), itemNameOf(order),
                                    order.getOrderNumber(), order.getTotalAmount()));
            return "redirect:/hall/bills/" + id;

        } catch (jp.komeko.order.service.OrderRejectedException e) {
            redirectAttributes.addFlashAttribute("flashErrors", e.getReasons());

        } catch (MenuService.MenuItemNotFoundException | TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall/bills/" + id;
        }
        return "redirect:/hall/bills/" + id + "/orders/new?itemId=" + itemId;
    }

    /** 選ぶ画面に並べるカテゴリと商品。掲載中のものだけ（品切れも出す。理由は画面側の説明を参照）。 */
    private List<PickCategory> pickCategories() {
        List<PickCategory> groups = new ArrayList<>();
        Long currentId = null;
        List<MenuItem> current = null;
        // itemsForSoldOutPanel はカテゴリ順 → 商品の並び順で返ってくるので、
        // 順に見て切り替わったところで束ねればよい（並べ替え直す必要がない）
        for (MenuItem item : menuService.itemsForSoldOutPanel()) {
            Long categoryId = item.getCategory().getId();
            if (!categoryId.equals(currentId)) {
                current = new ArrayList<>();
                groups.add(new PickCategory(item.getCategory().getName(), current));
                currentId = categoryId;
            }
            current.add(item);
        }
        return groups;
    }

    /**
     * 時価・おまかせの品（価格が 0 以下のもの）。
     *
     * <p>売り切れかどうかで絞りません。時価の品は「価格 0 円のまま注文されるのを
     * 防ぐため」に、はじめから売り切れとして登録されています。
     * 絞ると<b>この画面から 1 品も出てこなくなります</b>。
     */
    private List<MenuItem> marketPricedItems() {
        return menuService.itemsForSoldOutPanel().stream()
                .filter(i -> i.getPrice() <= 0)
                .toList();
    }

    /** 入れた品の名前（1 品ずつ入れるので、明細は必ず 1 行）。 */
    private static String itemNameOf(Order order) {
        return order.getLines().isEmpty() ? "商品" : order.getLines().get(0).getMenuItemName();
    }

    /**
     * 人数を変更する。
     *
     * <p>テーブルチャージは「単価 × 人数」なので、人数を直すとご請求額も変わります。
     * 再計算は {@link TableService#changeGuestCount(Long, int)} の中で行われます。
     */
    @PostMapping("/bills/{id}/guests")
    public String changeGuests(@PathVariable Long id,
                               @RequestParam(defaultValue = "1") int guestCount,
                               RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.changeGuestCount(id, guestCount);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "人数を %d 名に変更しました（テーブルチャージ ¥%,d）"
                            .formatted(bill.getGuestCount(), bill.getTableChargeAmount()));

        } catch (IllegalStateException e) {
            // 会計済みの伝票を直そうとした、など
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * テーブルチャージをいただかない人数を変更する。
     *
     * <p><b>人数を減らして調整しないこと。</b>
     * 未就学のお子さま連れの 4 名を「2 名」にしてしまうと、
     * チャージは合いますが<b>売上の客数が 2 名になります</b>。
     * 客単価も席の回転も、そこから全部ずれていきます。
     * 来た人数は来た人数のまま置いて、チャージの対象だけをここで外します。
     *
     * <p>人数と同じく {@code select} で受けるので、値の範囲は画面側でも
     * 絞られていますが、{@link TableService#changeChargeExemptCount} が
     * 0〜人数の範囲に丸め直します（URL を直接叩かれても壊れないように）。
     */
    @PostMapping("/bills/{id}/charge-exempt")
    public String changeChargeExempt(@PathVariable Long id,
                                     @RequestParam(defaultValue = "0") int chargeExemptCount,
                                     RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.changeChargeExemptCount(id, chargeExemptCount);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    bill.getChargeExemptCount() == 0
                            ? "チャージ除外を解除しました（%d 名ぶん ¥%,d）"
                                    .formatted(bill.getChargeableGuestCount(), bill.getTableChargeAmount())
                            : "%d 名をチャージ対象外にしました（%d 名ぶん ¥%,d）"
                                    .formatted(bill.getChargeExemptCount(),
                                            bill.getChargeableGuestCount(), bill.getTableChargeAmount()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * お会計をはじめる（追加注文を止める）。
     *
     * <p>レジで金額を読み上げている最中に、お客さまの手元から
     * 追加のご注文が入ると、<b>読み上げた金額と請求額が食い違います</b>。
     * この操作で伝票を「お会計待ち」にすると、その卓からの注文が止まります。
     *
     * <p>締めるわけではないので、売上はまだ確定しません。
     * 「やっぱりもう一杯」と言われたら {@link #resumeOrdering} で戻せます。
     */
    @PostMapping("/bills/{id}/checkout")
    public String startCheckout(@PathVariable Long id,
                                RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.startCheckout(id);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」をお会計待ちにしました。この卓からの追加注文は止まっています"
                            .formatted(bill.getDiningTable().getName()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * 注文を再開する（お会計待ちをやめる）。
     *
     * <p>「お会計おねがいします」のあとで追加のご注文をいただいたときに使います。
     * 会計を取り消す（{@code reopen}）のとは別物で、こちらは<b>まだ締めていない</b>
     * 伝票を注文できる状態に戻すだけです。売上は動きません。
     */
    @PostMapping("/bills/{id}/resume")
    public String resumeOrdering(@PathVariable Long id,
                                 RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.resumeOrdering(id);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」のご注文を再開しました".formatted(bill.getDiningTable().getName()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * 明細ごとの「深夜料金の対象にする／しない」を切り替える。
     *
     * <p>打ち直しの救済用です。詳しくは
     * {@code OrderService#setLateNightExempt} と {@code Order#lateNightExempt} を読んでください。
     *
     * <p>チェックボックスを押した瞬間に送信されるので、
     * {@code exempt} には切り替え<b>後</b>の状態が入ります。
     * チェックが外れた状態で送信されるとパラメータ自体が来ないため、
     * {@code defaultValue} で false を受け取ります（会計の締めと同じ理由）。
     */
    @PostMapping("/bills/{id}/orders/{orderId}/late-night")
    public String toggleOrderLateNight(@PathVariable Long id,
                                       @PathVariable Long orderId,
                                       @RequestParam(defaultValue = "false") boolean exempt,
                                       @AuthenticationPrincipal StaffUserDetails user,
                                       RedirectAttributes redirectAttributes) {
        try {
            orderService.setLateNightExempt(orderId, exempt, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    exempt ? "この注文を深夜料金の対象外にしました"
                           : "この注文を深夜料金の対象に戻しました");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * お会計（伝票を締める）。この店の売上が確定する操作です。
     *
     * <p><b>{@code applyLateNight} が boolean なのに defaultValue が要る理由</b><br>
     * HTML のチェックボックスは、チェックが外れていると<b>そもそも送信されません</b>。
     * 既定値を指定しておかないと「パラメータが無い」でエラーになるため、
     * 送られてこなかった＝チェックを外した＝false として受け取ります。
     *
     * <p>締めたあとは伝票の詳細に戻します。一覧（{@code /hall}）は開いている伝票しか
     * 並ばないので、そちらへ戻すと<b>直前に締めた伝票にたどり着けなくなり</b>、
     * 誤会計に気づいたときの取り消しができなくなるためです。
     *
     * <p><b>{@code paymentMethod} に既定値を置いていない理由</b><br>
     * 現金かカードかは<b>毎回お客さまに聞いて決まること</b>で、
     * 推測できる既定値がありません。「未選択なら現金」にすると、
     * 押し忘れたぶんが現金売上に化けて、閉店後のレジ締めで
     * <b>金庫の中身が足りない</b>ように見えます（実際にはカードで受け取っている）。
     * どちらか選ぶまで締められないほうが安全なので、
     * 未選択（＝ラジオが送られてこない）は {@code null} で受けて
     * {@code TableService} 側で突き返します。
     */
    @PostMapping("/bills/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(defaultValue = "false") boolean applyLateNight,
                        @RequestParam(required = false) String note,
                        @RequestParam(required = false) SettlementMethod paymentMethod,
                        @AuthenticationPrincipal StaffUserDetails user,
                        RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.closeSession(
                    id, applyLateNight, staffNameOf(user), noteOf(note), paymentMethod);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」のお会計を締めました。ご請求額 ¥%,d（内消費税 ¥%,d）／%s"
                            .formatted(bill.getDiningTable().getName(),
                                    bill.getTotalAmount(), bill.getTaxAmount(),
                                    bill.getPaymentMethod().getLabel()));

        } catch (IllegalArgumentException e) {
            // お支払い方法が選ばれていない。伝票はまだ開いたままなので、選び直せば済む。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (IllegalStateException e) {
            // すでに会計済み。二人が同時に締めようとしたときなどに起こる。
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * 会計を取り消して伝票を開け直す（誤会計のリカバリ）。
     *
     * <p>締めたあとで「まだ追加注文があった」「別の卓と間違えた」と分かることがあります。
     * 売上に直結する操作なので、誰が取り消したかをログに残します
     * （記録は {@link TableService#reopenSession(Long, String)} の中）。
     */
    /**
     * お客さまからの呼び出しに「対応した」を付ける。
     *
     * <p>行は消しません。何回呼ばれたか・どれくらい待たせたかは、
     * あとから店の動きを見直すときの材料になります。
     *
     * <p>二人が同時に押すことは普通に起こります（画面は数秒ごとに描き直される）。
     * あとから押した人の名前で上書きすると、先に向かった人の記録が消えるので、
     * すでに対応済みなら何もしません（{@code ServiceCall#handle}）。
     */
    @PostMapping("/calls/{id}/handle")
    public String handleCall(@PathVariable Long id,
                             @AuthenticationPrincipal StaffUserDetails user,
                             RedirectAttributes redirectAttributes) {
        serviceCallService.handle(id, staffNameOf(user));
        redirectAttributes.addFlashAttribute("flashInfo", "呼び出しに対応しました");
        return "redirect:/hall";
    }

    @PostMapping("/bills/{id}/reopen")
    public String reopen(@PathVariable Long id,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.reopenSession(id, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashInfo",
                    "「%s」の会計を取り消し、伝票を開き直しました".formatted(bill.getDiningTable().getName()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    // ========================================================================
    //  ご案内（＋新規お客様 → 人数と卓を選ぶ）
    // ========================================================================

    /**
     * 「＋新規お客様」の入力画面。人数と卓をここで選ぶ（2026-09-11）。
     *
     * <p><b>盤面から「空席」と「片付け待ち」の枠を外した代わりの入口です。</b>
     * 以前は空席カードごとに人数の選択とご案内ボタンが付いていましたが、
     * 卓の数だけ同じ部品が並ぶうえ、片付け待ちの卓は別の枠に分かれていて、
     * 「片付け完了 → ご案内」の 2 手が必要でした。
     *
     * <p><b>伝票の無い卓は、片付け待ちも含めて全部並べます。</b>
     * 片付け待ちを隠すと「なぜあの卓が出てこないのか」が画面から分からず、
     * 片付け完了を押す場所を別に用意することになります。
     * 並べたうえで状態を出し、選ばれたら {@link TableService#seat} が
     * 片付け完了も一緒に記録します。
     */
    /**
     * ★ 2026-09-12 にモーダル化したので、この別ページは使っていません。
     *
     * <p>盤面の「＋ 新規お客様」は {@code <dialog>} を開く形に変わりました。
     * この口を残してあるのは、<b>JavaScript が動かない端末の逃げ道</b>としてです
     * （店のタブレットが古い・拡張機能で JS が切られている、などの場合）。
     * 盤面からのリンクは無くなっているので、通常は誰も来ません。
     *
     * <p>消さないのは、消すとその端末でご案内の手段がまったく無くなるためです。
     */
    @GetMapping("/seat/new")
    public String seatForm(Model model) {
        List<TableSession> bills = tableService.openSessions();
        List<DiningTable> tables = tableService.activeTables();

        Set<Long> occupiedTableIds = new HashSet<>();
        for (TableSession bill : bills) {
            occupiedTableIds.add(bill.getDiningTable().getId());
        }

        List<DiningTable> seatTables = new ArrayList<>();
        for (DiningTable table : tables) {
            if (!occupiedTableIds.contains(table.getId())) {
                seatTables.add(table);
            }
        }

        model.addAttribute("seatTables", seatTables);
        // エリアの区切りは盤面と同じ仕組み（groupByArea の説明参照）
        model.addAttribute("seatGroups", groupByArea(tables, seatTables, table -> table));
        model.addAttribute("guestOptions", guestOptions(MAX_GUEST_CHOICE));
        model.addAttribute("defaultGuestCount", DEFAULT_GUEST_COUNT);
        return "hall/seat-new";
    }

    /**
     * ご案内する（伝票を開く）。片付け待ちの卓なら片付け完了も同時に記録する。
     *
     * <p>お客さまが QR を読んだ時点でも伝票は自動で開きますが、
     * 「先に席へ通してから、あとでゆっくり注文する」流れが普通なので、
     * ホール側からも開けるようにしています。
     * すでに開いていれば {@link TableService#openSession(Long, int)} が
     * 既存の伝票を返してくれるので、二重に伝票ができることはありません。
     */
    @PostMapping("/seat")
    public String seat(@RequestParam Long tableId,
                       @RequestParam(defaultValue = "1") int guestCount,
                       @RequestParam(required = false) Integer guestCountOther,
                       RedirectAttributes redirectAttributes) {
        // 9 名以上は入力欄で受ける（チップは 1〜8。お客さま側の table-start.html と同じ形）。
        // ★ 判定をサーバ側に置いているのは、JavaScript が動かなくても
        //   入力欄から送れば通るようにするため。画面側だけの対策では素通りする
        int guests = (guestCountOther != null && guestCountOther >= 9)
                ? guestCountOther
                : guestCount;
        try {
            TableService.SeatResult result = tableService.seat(tableId, guests);
            TableSession bill = result.bill();
            String tableName = bill.getDiningTable().getName();

            // 片付けは人の判断なので、何を記録したのかを画面にも出す。
            // 「いつのまにか片付け完了になっていた」と思われないようにするため
            redirectAttributes.addFlashAttribute("flashSuccess", result.markedCleaned()
                    ? "「%s」を片付け完了にして、%d 名さまをご案内しました"
                            .formatted(tableName, bill.getGuestCount())
                    : "「%s」に %d 名さまをご案内しました"
                            .formatted(tableName, bill.getGuestCount()));
            log.info("ホールからご案内: 卓={} 人数={} 片付け完了={}",
                    tableName, bill.getGuestCount(), result.markedCleaned());

        } catch (OrderRejectedException e) {
            // 営業時間外など。TableNotReadyException もこの型だが、
            // 片付け待ちは seat() が先に旗を下ろすので、ここには来ない
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall";
    }

    /**
     * 片付け完了。卓を空席（ご案内できる状態）に戻す。
     *
     * <p>会計済み（片付け待ち）は伝票ではなく卓の旗なので、ここは伝票を触らない。旗を下ろすだけ。
     *
     * <p><b>「＋新規お客様」と二本立てになっているのは、担当が違うからです。</b>
     * {@link #seat} は「次の組を通すついでに片付けも記録する」入口で、
     * こちらは「先に皿だけ下げた」ときの入口です。
     * 片付けてから次の組が来るまで間が空くのが普通なので、両方要ります。
     */
    @PostMapping("/tables/{tableId}/cleaned")
    public String markCleaned(@PathVariable Long tableId,
                              RedirectAttributes redirectAttributes) {
        try {
            DiningTable table = tableService.markCleaned(tableId);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」を片付け完了にしました。ご案内できます".formatted(table.getName()));
        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 会計を締める画面が必要とする、伝票ごとの 2 つの値。
     *
     * @param lateNightDefault         深夜料金のチェックを初期状態で入れるか
     * @param totalIfLateNightApplied  深夜料金を<b>付けた場合</b>のご請求額（確認ダイアログ用）
     */
    public record CheckoutView(boolean lateNightDefault, int totalIfLateNightApplied) {
    }

    /**
     * 伝票ページと盤面のモーダルで<b>同じ計算を使う</b>ための切り出し（2026-09-12）。
     *
     * <p>盤面にお会計モーダルを載せたとき、ここを書き写すと計算が 2 か所になります。
     * 下の免除まわりは分かりにくく、片方だけ直したときに
     * 「画面によって読み上げる金額が違う」という一番まずい形になるので、
     * 必ずこのメソッドを通してください。
     *
     * <p><b>チェックの初期状態</b><br>
     * 「いま深夜帯か」で決めてはいけません。深夜料金は<b>注文時刻ごと</b>に決まるので、
     * 23:30 に注文があった卓を 5:30 に会計することも、22:00 で終わった卓を
     * 23:30 に会計することもあります。開いている伝票は表示のたびに計算し直されているので、
     * その結果をそのまま初期状態にします。ただし一度スタッフが免除した伝票は、
     * 開け直しても外れたままにします（人の判断を計算結果で上書きしない）。
     *
     * <p><b>「付けた場合」の金額</b><br>
     * ふつうは {@code getTotalWithLateNight()} でよいのですが、免除フラグが立った伝票は
     * 再計算が NONE に強制されるため割増が常に 0 で、「付けた場合」がどこにも計算されていません。
     * そのままだとチェックを入れ直して締めるとき、ダイアログが割増抜きの金額を
     * 「深夜料金 込み」と読み上げ、実際に締まる金額のほうが高くなります。
     *
     * <p>ここで受け取る伝票は detached（表示専用）なので、免除を一時的に外して
     * 計算しても DB には書き戻りません。
     */
    private CheckoutView checkoutViewOf(TableSession bill) {
        boolean lateNightDefault = !bill.isLateNightWaived() && bill.isLateNightApplied();

        int totalIfLateNightApplied = bill.getTotalWithLateNight();
        if (bill.isActive() && bill.isLateNightWaived()) {
            ShopSetting current = shopSettingService.currentReadOnly();
            bill.setLateNightWaived(false);
            bill.recalculate(current::isLateNight);
            totalIfLateNightApplied = bill.getTotalWithLateNight();
            // 画面本体の表示は免除状態のままにしたいので、元に戻して計算し直す
            bill.setLateNightWaived(true);
            bill.recalculate(current::isLateNight);
        }
        return new CheckoutView(lateNightDefault, totalIfLateNightApplied);
    }

    /**
     * エリアの 1 区切り。{@code name} が null なら見出しを出さない
     * （全卓が未設定のときの、従来どおりの 1 グループ）。
     */
    public record AreaGroup<T>(String name, List<T> items) {
    }

    /** 未設定のエリアをまとめる見出し。 */
    private static final String AREA_OTHERS = "その他";

    /**
     * 項目をエリアごとに区切る（卓 2/3）。
     *
     * <p>エリアの並びは卓の並び順（sortOrder）に従い、未設定は「その他」として
     * 最後にまとめる。<b>全卓が未設定なら name=null の 1 グループ</b>を返し、
     * テンプレートは見出しを描かない——エリアを使わない店では
     * 導入前と見た目が変わらないようにするため。
     * 空のグループは返さない（空の見出しだけが並ぶのを防ぐ）。
     *
     * @param tables  エリアの語彙と並びの源（稼働中の全卓・並び順ソート済み）
     * @param items   区切りたいもの（伝票 or 卓）
     * @param tableOf 項目からその卓を取り出す関数
     */
    private <T> List<AreaGroup<T>> groupByArea(List<DiningTable> tables, List<T> items,
                                               java.util.function.Function<T, DiningTable> tableOf) {
        boolean hasAreas = tables.stream().anyMatch(DiningTable::hasArea);
        if (!hasAreas) {
            return List.of(new AreaGroup<>(null, items));
        }

        // 卓の並び順どおりにエリア名を集める（LinkedHashMap で出現順を保つ）
        Map<String, List<T>> byArea = new java.util.LinkedHashMap<>();
        for (DiningTable table : tables) {
            if (table.hasArea()) {
                byArea.putIfAbsent(table.getArea(), new ArrayList<>());
            }
        }
        byArea.put(AREA_OTHERS, new ArrayList<>());

        for (T item : items) {
            DiningTable table = tableOf.apply(item);
            String key = table.hasArea() ? table.getArea() : AREA_OTHERS;
            byArea.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        List<AreaGroup<T>> groups = new ArrayList<>();
        byArea.forEach((name, list) -> {
            if (!list.isEmpty()) {
                groups.add(new AreaGroup<>(name, list));
            }
        });
        return groups;
    }

    /**
     * 本日（営業日）の会計済み伝票を、画面に出せる形にして返す。
     *
     * <p><b>なぜ「値のコピー」に詰め替えるのか</b><br>
     * 一覧取得に使っている問い合わせは卓（diningTable）しか一緒に読み込みません。
     * このアプリは {@code open-in-view: false} なので、
     * テンプレートの中から {@code bill.orders} のような未読み込みの関連を触ると
     * {@code LazyInitializationException} で画面が真っ白になります。
     * 画面に出す値だけを取り出した record にしておけば、
     * <b>あとから誰かがテンプレートに 1 行足しても壊れません</b>。
     */
    private List<ClosedBillRow> closedBillsOfToday() {
        LocalDate businessDate = shopSettingService.currentBusinessDate();
        List<ClosedBillRow> rows = new ArrayList<>();
        for (TableSession bill : tableService.sessionsOf(businessDate)) {
            if (bill.getStatus() != SessionStatus.CLOSED) {
                continue;   // まだ開いている伝票は上の一覧に出ているので、ここでは省く
            }
            rows.add(new ClosedBillRow(
                    bill.getId(),
                    bill.getDiningTable().getName(),
                    bill.getGuestCount(),
                    bill.getTotalAmount(),
                    bill.getOpenedAt().format(TIME_FORMAT),
                    bill.getClosedAt() == null ? "―" : bill.getClosedAt().format(TIME_FORMAT),
                    (bill.getClosedBy() == null || bill.getClosedBy().isBlank()) ? "―" : bill.getClosedBy()));
        }
        return rows;
    }

    /**
     * 人数プルダウンの選択肢（1 〜 upTo）を作る。
     *
     * <p>テンプレート側でループの数字を作る方法もありますが、
     * 「いくつまで出すか」という決めごとは Java 側にまとめたほうが変更しやすいので
     * ここで用意しています。
     */
    private static List<Integer> guestOptions(int upTo) {
        List<Integer> options = new ArrayList<>();
        for (int n = 1; n <= upTo; n++) {
            options.add(n);
        }
        return options;
    }

    /**
     * 0 から始まる選択肢（チャージ除外人数用）。
     *
     * <p>{@link #guestOptions} と分けてあるのは、始まりが 1 か 0 かの違いが
     * <b>意味の違い</b>だからです。来店人数に 0 名はありませんが、
     * チャージ除外は 0 名（＝全員からいただく）が既定の状態です。
     */
    private static List<Integer> countOptions(int upTo) {
        List<Integer> options = new ArrayList<>();
        for (int n = 0; n <= upTo; n++) {
            options.add(n);
        }
        return options;
    }

    /**
     * 操作したスタッフの表示名を取り出す（null 安全）。
     *
     * <p>会計は「誰が締めたか」を必ず残したいので、
     * 取れなかった場合も空文字ではなく代わりの文字列を返します。
     */
    private static String staffNameOf(StaffUserDetails user) {
        if (user == null) {
            return "スタッフ";
        }
        String displayName = user.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            return user.getUsername();
        }
        return displayName;
    }

    /**
     * 会計メモを整える。
     *
     * <p>{@code TableSession.note} の列は 200 文字までなので、
     * 長すぎる値が来たら切り詰めます（DB でエラーになるのを防ぐ）。
     * 空欄のときは null にして「メモなし」と区別できるようにします。
     */
    private static String noteOf(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > NOTE_MAX_LENGTH ? trimmed.substring(0, NOTE_MAX_LENGTH) : trimmed;
    }

    /**
     * 例外のメッセージを画面に出せる形にする。
     *
     * <p>{@code List.of(...)} は要素に null を渡すと {@code NullPointerException} になります。
     * 「エラーを表示しようとして別のエラーが出る」のが一番やっかいなので、
     * ここで受け止めて代わりの文言に差し替えます。
     */
    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "その操作は行えませんでした";
        }
        return message;
    }

    /**
     * 会計済み伝票 1 行分の表示用データ。
     *
     * <p><b>record（レコード）とは</b><br>
     * 「値を持つだけの入れ物」を 1 行で書ける Java の記法です。
     * getter・equals・toString が自動で作られます。
     * ただし getter の名前は {@code getTableName()} ではなく {@code tableName()} なので、
     * Thymeleaf からは {@code ${row.tableName()}} のように<b>括弧付きで</b>呼びます。
     *
     * @param id             伝票 ID（詳細画面へのリンクに使う）
     * @param tableName      卓名
     * @param guestCount     人数
     * @param totalAmount    ご請求額（円・税込）
     * @param openedAtLabel  入店時刻（HH:mm）
     * @param closedAtLabel  会計時刻（HH:mm）
     * @param closedBy       会計したスタッフ名
     */
    public record ClosedBillRow(Long id,
                                String tableName,
                                int guestCount,
                                int totalAmount,
                                String openedAtLabel,
                                String closedAtLabel,
                                String closedBy) {
    }
}
