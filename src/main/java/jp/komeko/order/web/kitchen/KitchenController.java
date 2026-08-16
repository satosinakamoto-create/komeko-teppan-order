package jp.komeko.order.web.kitchen;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.dto.KitchenBoard;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厨房（キッチン）の画面を担当するコントローラ。
 *
 * <p><b>コントローラの役割</b><br>
 * 「HTTP のリクエストを受け取り → サービスに仕事を頼み → 画面に渡す値を用意する」だけの
 * 交通整理係です。金額の計算や状態遷移のルールといった<b>業務のルールは書きません</b>。
 * それらは {@link OrderService} など Service 側に置いてあります。
 * こう分けておくと、あとから「スマホアプリ用の API も生やしたい」となったときに
 * Service をそのまま使い回せます。
 *
 * <p><b>{@code @Controller} と {@code @RestController} の違い</b><br>
 * {@code @Controller} のメソッドが返す String は「テンプレート（HTML）の名前」です。
 * {@code "kitchen/board"} を返すと {@code templates/kitchen/board.html} が描画されます。
 * 一方 {@code @RestController} は戻り値をそのまま JSON などの本文として返します
 * （SSE を返す {@link KitchenStreamController} がそちらです）。
 *
 * <p><b>{@code @RequestMapping("/kitchen")}</b><br>
 * クラスに付けると、中のメソッドの URL すべてに共通の接頭辞が付きます。
 * {@code @PostMapping("/orders/{id}/status")} は実際には
 * {@code POST /kitchen/orders/{id}/status} になります。
 *
 * <p><b>PRG（Post → Redirect → Get）という作法</b><br>
 * 更新系（POST）の処理のあとは、HTML を直接返さず必ずリダイレクトします。
 * そうしないと、処理後の画面でブラウザの「再読み込み」を押したときに
 * 同じ POST がもう一度飛び、二重に状態を進めてしまいます。
 * 厨房は忙しい現場で連打も起きるので、この作法は特に大事です。
 */
@Controller
@RequestMapping("/kitchen")
public class KitchenController {

    private static final Logger log = LoggerFactory.getLogger(KitchenController.class);

    /** この分数以上たった注文は「遅れぎみ」として画面で赤くする。 */
    private static final long LATE_MINUTES = 15;

    /**
     * 店側からキャンセルしたときに既定で記録する理由。
     *
     * <p>チケット 1 枚 1 枚に理由の入力欄を置くとボードが狭くなり、
     * さらに入力中に自動リロードが走ると書きかけの文字が消えてしまいます。
     * そこで画面からは確認ダイアログだけを挟み、理由はこの既定値を送っています。
     */
    private static final String DEFAULT_CANCEL_REASON = "店舗都合";

    private final OrderService orderService;
    private final MenuService menuService;

    /**
     * コンストラクタインジェクション。
     *
     * <p>Spring は「このクラスを作るのに OrderService と MenuService が要るんだな」と判断して、
     * 自動で渡してくれます（DI＝依存性の注入）。
     * フィールドに {@code @Autowired} を付ける書き方もありますが、
     * コンストラクタで受け取る形にすると
     * <ul>
     *   <li>フィールドを {@code final} にできる（あとから差し替わらない＝安全）</li>
     *   <li>テストで自分で {@code new} するときに何が必要か一目で分かる</li>
     * </ul>
     * という利点があるため、現在はこちらが推奨されています。
     * 引数が 1 つでも複数でも、コンストラクタが 1 つだけなら {@code @Autowired} は不要です。
     */
    public KitchenController(OrderService orderService, MenuService menuService) {
        this.orderService = orderService;
        this.menuService = menuService;
    }

    // ========================================================================
    //  厨房ボード
    // ========================================================================

    /**
     * 厨房ボード（受付／調理中／お渡し可の 3 レーン）を表示する。
     *
     * <p>{@link Model} に入れた値が、そのままテンプレート側で
     * {@code ${board}} のように参照できます。
     */
    @GetMapping
    public String board(Model model) {
        KitchenBoard board = orderService.kitchenBoard();

        model.addAttribute("board", board);
        // 3 レーンを「見出し・CSS 修飾クラス・注文リスト」の組にして渡す。
        // テンプレート側で同じチケットの HTML を 3 回コピペしなくて済む。
        model.addAttribute("lanes", List.of(
                new BoardLane("受付", "lane--received", board.received()),
                new BoardLane("調理中", "lane--cooking", board.cooking()),
                new BoardLane("お渡し可", "lane--ready", board.ready())));
        model.addAttribute("lateMinutes", LATE_MINUTES);
        // レイアウト（layout/staff.html）のナビで、いまいる場所に色を付けるための目印
        model.addAttribute("activeNav", "kitchen");
        return "kitchen/board";
    }

    /**
     * 注文の状態を次へ進める。
     *
     * <p>{@code status} には {@link OrderStatus} の名前（COOKING / READY / COMPLETED …）が
     * 文字列で届きます。{@code OrderStatus.valueOf(...)} で enum に戻しますが、
     * URL を直接叩かれて存在しない名前が来ると {@link IllegalArgumentException} が飛ぶので、
     * ここで受け止めます。
     *
     * <p><b>なぜ例外をここで catch するのか</b><br>
     * 状態遷移の違反（例：すでに受渡済の伝票をもう一度進めようとした）は
     * {@link IllegalStateException} になります。これを素通しすると
     * {@code GlobalExceptionHandler} がエラーページを表示し、
     * 厨房ボードから離脱してしまいます。忙しい現場では致命的に使いづらいので、
     * この画面に限ってはメッセージだけ出してボードに戻します。
     *
     * <p><b>{@code @AuthenticationPrincipal}</b><br>
     * ログイン中のユーザー（{@link StaffUserDetails}）を引数として受け取れる指定です。
     * セキュリティ設定で {@code /kitchen/**} はログイン必須なので基本 null になりませんが、
     * 念のため {@link #staffNameOf(StaffUserDetails)} で null 安全に扱います。
     */
    @PostMapping("/orders/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam String status,
                               @AuthenticationPrincipal StaffUserDetails user,
                               RedirectAttributes redirectAttributes) {
        try {
            OrderStatus next = OrderStatus.valueOf(status);
            Order order = orderService.changeStatus(id, next, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "#%d を「%s」にしました".formatted(order.getOrderNumber(), next.getStaffLabel()));

        } catch (IllegalArgumentException e) {
            // valueOf の失敗（存在しない状態名）。通常の操作では起こらない。
            log.warn("不明な状態が指定されました: {}", status);
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("その状態には変更できません（%s）".formatted(status)));

        } catch (IllegalStateException e) {
            // 状態遷移の違反。二人が同時に同じ伝票を触ったときなどに起こる。
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (OrderService.OrderNotFoundException e) {
            // 伝票が消えている（日付が変わった直後など）。これもボードに留まって知らせる。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/kitchen";
    }

    /**
     * 店側から注文をキャンセルする（材料切れ・お客さんが来ないなど）。
     *
     * <p>{@code required = false} を付けた引数は、フォームから送られてこなくても
     * エラーにならず null になります。
     */
    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            Order order = orderService.cancelByStaff(id, cancelReasonOf(reason), staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashInfo",
                    "#%d をキャンセルしました".formatted(order.getOrderNumber()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (OrderService.OrderNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/kitchen";
    }

    // ========================================================================
    //  品切れ管理
    // ========================================================================

    /**
     * 品切れ管理パネル。
     *
     * <p>カテゴリごとに見出しを付けて並べたいので、
     * {@code カテゴリ名 → 商品リスト} の形に組み替えてから画面に渡します。
     *
     * <p>{@link LinkedHashMap} を使うのは<b>入れた順番が保たれる</b>ためです。
     * 普通の {@code HashMap} は順不同なので、並び順に意味がある画面では使えません。
     * （{@code itemsForSoldOutPanel()} は「カテゴリ順 → 商品の並び順」で返ってくるので、
     * その順に詰めれば見出しの順序もそのまま正しくなります）
     */
    @GetMapping("/stock")
    public String stock(Model model) {
        List<MenuItem> items = menuService.itemsForSoldOutPanel();

        Map<String, List<MenuItem>> byCategory = new LinkedHashMap<>();
        for (MenuItem item : items) {
            // category は EntityGraph で一緒に読み込み済みなので、ここで触っても
            // LazyInitializationException にはならない（open-in-view: false のため要注意な箇所）
            String categoryName = item.getCategory().getName();
            byCategory.computeIfAbsent(categoryName, key -> new ArrayList<>()).add(item);
        }

        long soldOutCount = items.stream().filter(MenuItem::isSoldOut).count();

        model.addAttribute("itemsByCategory", byCategory);
        model.addAttribute("itemCount", items.size());
        model.addAttribute("soldOutCount", soldOutCount);
        model.addAttribute("activeNav", "stock");
        return "kitchen/stock";
    }

    /**
     * 品切れフラグを反転させる（ワンタップで「売り切れ／販売再開」）。
     *
     * <p>戻り先は品切れパネルです。厨房ボードに飛ばすと、
     * 続けて何品も操作したいときに毎回パネルを開き直すことになるためです。
     */
    @PostMapping("/stock/{itemId}/toggle")
    public String toggleSoldOut(@PathVariable Long itemId, RedirectAttributes redirectAttributes) {
        try {
            boolean soldOut = menuService.toggleSoldOut(itemId);
            // 商品名まで出したいので、トグル後の状態をもう一度読み直して名前を取る。
            // （toggleSoldOut は「品切れになったか」の真偽値しか返さないため）
            String name = menuService.itemWithOptions(itemId).getName();

            if (soldOut) {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "「%s」を品切れにしました。お客さまの画面から注文できなくなります".formatted(name));
            } else {
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "「%s」の販売を再開しました".formatted(name));
            }
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/kitchen/stock";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 操作したスタッフの表示名を取り出す（null 安全）。
     *
     * <p>取れなかった場合でも「誰が触ったか分からない」と記録が残るよう、
     * 空文字ではなく代替の文字列を返します。
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
     * 例外のメッセージを画面に出せる形にする。
     *
     * <p>{@code List.of(...)} は要素に null を渡すと {@code NullPointerException} になります。
     * 例外のメッセージは必ず入っている想定ですが、
     * 「エラーを表示しようとして別のエラーが出る」のが一番やっかいなので、
     * ここで受け止めて代替の文言に差し替えておきます。
     */
    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "その操作は行えませんでした";
        }
        return message;
    }

    /**
     * キャンセル理由を整える。
     *
     * <p>{@code Order.canceledReason} の列は 100 文字までなので、
     * 長すぎる値が来たら切り詰めます（DB でエラーになるのを防ぐ）。
     */
    private static String cancelReasonOf(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_CANCEL_REASON;
        }
        String trimmed = reason.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    /**
     * 画面に渡すレーン 1 本分のデータ。
     *
     * <p><b>record（レコード）とは</b><br>
     * 「値を持つだけの入れ物」を 1 行で書ける Java の記法です。
     * getter・equals・toString が自動で作られます。
     * ただし getter の名前は {@code getTitle()} ではなく {@code title()} なので、
     * Thymeleaf からは {@code ${lane.title()}} のように<b>括弧付きで</b>呼びます。
     *
     * @param title    レーンの見出し（受付／調理中／お渡し可）
     * @param modifier CSS の修飾クラス（lane--received など）
     * @param orders   そのレーンに並ぶ注文
     */
    public record BoardLane(String title, String modifier, List<Order> orders) {

        /** レーンに並ぶ件数。 */
        public int size() {
            return orders.size();
        }

        /** 1 件もないか（「注文はありません」を出す判定に使う）。 */
        public boolean isEmpty() {
            return orders.isEmpty();
        }
    }
}
