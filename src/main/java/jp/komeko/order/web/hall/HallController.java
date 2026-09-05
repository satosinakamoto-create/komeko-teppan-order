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
     * 「卓ごとの注文」に出す、注文 1 回ぶん。
     *
     * <p><b>なぜ {@link Order} をそのまま渡さずに包むのか</b><br>
     * 受付時刻の {@code createdAt} は {@link LocalDateTime} 型で、そのまま画面に出すと
     * {@code 2026-08-29T18:42:03.412} という機械向けの表記になります。
     * かといってテンプレート側で書式を組み立てると
     * 「動く環境と動かない環境がある」という厄介な問題を抱えがちなので、
     * このアプリでは<b>表示のための整形は Java 側で済ませる</b>ことにしています
     * （{@code AdminOrderController.OrderRow} と同じ考え方）。
     *
     * <p>{@code canceled} をわざわざ持たせているのは、テンプレートに
     * {@code ${order.status.name() == 'CANCELED'}} という<b>文字列の比較</b>を
     * 書かせないためです。enum の名前を文字列で書くと、あとから enum を直したときに
     * コンパイラが教えてくれず、画面だけが静かに壊れます。
     *
     * @param order      注文そのもの（明細・状態はここから読む）
     * @param receivedAt 受付時刻を "HH:mm" に整えた文字列
     * @param canceled   取り消された注文か（薄く出すかどうかの判断に使う）
     */
    public record TableOrderRow(Order order, String receivedAt, boolean canceled) {
    }

    /**
     * 「卓ごとの注文」の、卓 1 つぶん。
     *
     * @param bill   その卓の伝票（卓名・人数・滞在時間・ご請求額はここから読む）
     * @param orders その伝票にぶら下がる注文を時刻の古い順に並べたもの
     */
    public record TableOrders(TableSession bill, List<TableOrderRow> orders) {
    }

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
        List<TableSession> bills = tableService.openSessions();
        List<DiningTable> tables = tableService.activeTables();

        // 開いている伝票が使っている卓の ID を集める。
        // Set（集合）にすると contains の判定が速く、重複も自動で消えます。
        Set<Long> occupiedTableIds = new HashSet<>();
        for (TableSession bill : bills) {
            occupiedTableIds.add(bill.getDiningTable().getId());
        }

        List<DiningTable> vacantTables = new ArrayList<>();
        for (DiningTable table : tables) {
            if (!occupiedTableIds.contains(table.getId())) {
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
        model.addAttribute("tableOrders", tableOrdersOf(bills));
        model.addAttribute("vacantTables", vacantTables);
        model.addAttribute("occupiedCount", bills.size());
        model.addAttribute("vacantCount", vacantTables.size());
        model.addAttribute("guestTotal", guestTotal);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("guestOptions", guestOptions(MAX_GUEST_CHOICE));
        model.addAttribute("defaultGuestCount", DEFAULT_GUEST_COUNT);
        model.addAttribute("closedBills", closedBillsOfToday());
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
        model.addAttribute("lateNightDefault",
                !bill.isLateNightWaived() && bill.isLateNightApplied());
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
        model.addAttribute("totalIfLateNightApplied", totalIfLateNightApplied);
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
    //  ご案内（空席に伝票を開く）
    // ========================================================================

    /**
     * スタッフが席にご案内する（伝票を開く）。
     *
     * <p>お客さまが QR を読んだ時点でも伝票は自動で開きますが、
     * 「先に席へ通してから、あとでゆっくり注文する」流れが普通なので、
     * ホール側からも開けるようにしています。
     * すでに開いていれば {@link TableService#openSession(Long, int)} が
     * 既存の伝票を返してくれるので、二重に伝票ができることはありません。
     */
    @PostMapping("/tables/{tableId}/open")
    public String openTable(@PathVariable Long tableId,
                            @RequestParam(defaultValue = "1") int guestCount,
                            RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.openSession(tableId, guestCount);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」に %d 名さまをご案内しました".formatted(
                            bill.getDiningTable().getName(), bill.getGuestCount()));
            log.info("ホールからご案内: 卓={} 人数={}",
                    bill.getDiningTable().getName(), bill.getGuestCount());

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 在席の伝票を「卓ごとの注文」の形に組み替える。
     *
     * <p>上の伝票カードには金額しか出ないため、何を頼まれているかを知るには
     * 卓を 1 つずつ開くしかありませんでした。この一覧は
     * <b>どの卓が何を頼んでいるかを 1 画面で見る</b>ためのものです。
     *
     * <p><b>取り消した注文も残します。</b>
     * 「さっき頼んだのに来ない」の確認や、打ち直しの経緯を追うのに要るためです。
     * 請求額には入りません（{@code TableSession#recalculate} がキャンセルを除いて計算します）。
     * 伝票詳細（{@code hall/bill.html}）が {@code getBillableOrders()} を使って
     * キャンセルを隠しているのとは、意図的に扱いを変えています。
     * あちらは<b>お客さまに見せて金額を confirm する画面</b>、
     * こちらは<b>店が現場を把握する画面</b>だからです。
     *
     * <p><b>ここで {@code bill.getOrders()} の中身に触れてよい理由</b><br>
     * {@link TableService#openSessions()} が明細（{@code lines}）とオプションまで
     * 読み終えてから返しているからです。このアプリは {@code open-in-view: false} なので、
     * 読み終えていない関連に画面から触ると {@code LazyInitializationException} で
     * 画面ごと落ちます。この前提は {@code TableServiceIntegrationTest} で固定してあります。
     *
     * <p>並び順は {@code TableSession.orders} の {@code @OrderBy("createdAt ASC, id ASC")}
     * がそのまま効くので、ここでは並べ替えません。
     */
    private List<TableOrders> tableOrdersOf(List<TableSession> bills) {
        List<TableOrders> result = new ArrayList<>();
        for (TableSession bill : bills) {
            List<TableOrderRow> rows = new ArrayList<>();
            for (Order order : bill.getOrders()) {
                rows.add(new TableOrderRow(
                        order,
                        order.getCreatedAt().format(TIME_FORMAT),
                        order.getStatus() == OrderStatus.CANCELED));
            }
            result.add(new TableOrders(bill, rows));
        }
        return result;
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
