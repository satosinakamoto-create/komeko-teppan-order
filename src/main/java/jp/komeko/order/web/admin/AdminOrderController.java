package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Order;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 注文履歴の画面（管理者のみ）。
 *
 * <p>厨房画面（{@code /kitchen}）が「いま作っているもの」を見るのに対して、
 * こちらは「終わったものも含めて、その日 1 日ぶんを振り返る」ための画面です。
 * キャンセル済み・受渡済も含めて全部出ます。
 *
 * <p><b>イートインでの位置づけ</b><br>
 * お会計の単位は注文ではなく<b>伝票（卓の 1 回の来店）</b>です。
 * この画面は伝票ではなく<b>1 回ぶんの注文</b>を並べるので、
 * 同じ組のお客さまの追加注文は別々の行として出ます。
 * どの卓からの注文かは「卓」の列を見てください。
 * お会計そのものはホール画面（{@code /hall}）の担当です。
 */
@Controller
@RequestMapping("/admin/orders")
public class AdminOrderController {

    /** 受付時刻の表示形式（"11:23"）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 見出し用「2026年8月16日(日)」。 */
    private static final DateTimeFormatter TITLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN);

    /** {@code Order.canceledReason} の列の長さ。これを超える入力は切り詰める。 */
    private static final int REASON_MAX_LENGTH = 100;

    private final OrderService orderService;
    private final ShopSettingService shopSettingService;

    public AdminOrderController(OrderService orderService, ShopSettingService shopSettingService) {
        this.orderService = orderService;
        this.shopSettingService = shopSettingService;
    }

    // ========================================================================
    //  一覧
    // ========================================================================

    /**
     * 画面に出す 1 行ぶんのデータ。
     *
     * <p><b>なぜ {@link Order} をそのまま渡さずに包むのか</b><br>
     * 受付時刻の {@code createdAt} は {@link java.time.LocalDateTime} 型で、
     * そのまま画面に出すと {@code 2026-08-16T11:23:45.123} という機械向けの表記になります。
     * かといってテンプレート側で書式を組み立てると、
     * 「動く環境と動かない環境がある」という厄介な問題を抱えがちです。
     * そこで<b>表示のための整形は Java 側で済ませ、出来上がった文字列を渡す</b>ことにしました。
     * このような「画面表示専用の小さな器」をビューモデルと呼びます。
     *
     * @param order      注文そのもの（明細・状態などはここから読む）
     * @param receivedAt 受付時刻を "HH:mm" に整えた文字列
     */
    public record OrderRow(Order order, String receivedAt) {
    }

    /**
     * 指定営業日の注文一覧。
     *
     * <p>日付を省略したら「いまの営業日」を見ます。
     * 深夜 0〜5 時（既定）の注文は前日の営業日として集計されるので、
     * 暦の今日とは 1 日ずれることがあります。
     */
    @GetMapping
    public String list(@RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       Model model) {

        LocalDate target = (date != null) ? date : shopSettingService.currentBusinessDate();

        // ordersOf() は明細とオプションまで読み終えた状態で返してくれる。
        // このアプリは open-in-view: false なので、画面を描く段階では DB 接続が無く、
        // 「サービスを抜けるまでに必要なものを読み終えておく」ことが必須になる。
        //
        // 卓名（${o.tableName}）を画面に出せるのも同じ理由で、
        // OrderRepository 側の @EntityGraph に session と session.diningTable が
        // 指定されているおかげ。ここを外すと一覧が LazyInitializationException で落ちる。
        List<Order> orders = orderService.ordersOf(target);
        List<OrderRow> rows = orders.stream()
                .map(o -> new OrderRow(o, o.getCreatedAt().format(TIME_FORMAT)))
                .toList();

        model.addAttribute("activeNav", "admin");
        model.addAttribute("rows", rows);

        // 日付は表示用（日本語）と URL 用（ISO 文字列）を分けて渡す。
        // LocalDate をそのままリンクの引数にすると、環境によっては
        // "2026/08/16" のような形に変換され、リンク先で 400 エラーになるため。
        model.addAttribute("dateIso", target.toString());
        model.addAttribute("dateLabel", target.format(TITLE_FORMAT));
        model.addAttribute("prevDateIso", target.minusDays(1).toString());
        model.addAttribute("nextDateIso", target.plusDays(1).toString());

        return "admin/orders";
    }

    // ========================================================================
    //  管理者によるキャンセル
    // ========================================================================

    /**
     * 注文を管理者権限でキャンセルする。
     *
     * <p><b>{@code @AuthenticationPrincipal} とは</b><br>
     * ログイン中のユーザー情報（{@link StaffUserDetails}）を引数として受け取るための指定です。
     * 「誰がキャンセルしたか」を記録しておくと、
     * あとで「この注文なぜ消えてるの？」となったときに追跡できます。
     *
     * <p>キャンセルすると、その注文はぶら下がっている伝票の小計からも自動的に外れます
     * （{@code OrderService#cancelByStaff} が伝票を計算し直します）。
     * まだお会計していない卓なら、お客さまに出す金額もその場で下がります。
     *
     * <p>状態遷移のルール違反（受渡済のものをキャンセルしようとした等）は
     * {@code Order#changeStatus} が {@link IllegalStateException} を投げます。
     * ここで受け止めて一覧画面にメッセージを出します。受け止めないと
     * {@code GlobalExceptionHandler} が全画面のエラーページへ飛ばしてしまい、
     * 一覧に戻るまで一手間かかってしまいます。
     */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @RequestParam(required = false) String date,
                         @AuthenticationPrincipal StaffUserDetails staff,
                         RedirectAttributes redirectAttributes) {

        String staffName = (staff == null) ? "管理者" : staff.getDisplayName();

        try {
            Order canceled = orderService.cancelByStaff(id, normalizeReason(reason), staffName);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "注文 #%d（%s）をキャンセルしました。伝票の合計からも外れています"
                            .formatted(canceled.getOrderNumber(), canceled.getTableName()));
        } catch (IllegalStateException | OrderService.OrderNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }

        // 見ていた日付のまま一覧へ戻す。
        // addFlashAttribute（1 回だけ渡すデータ）ではなく addAttribute を使うと、
        // リダイレクト先 URL のクエリ文字列として安全にエスケープして付けてくれる。
        if (date != null && !date.isBlank()) {
            redirectAttributes.addAttribute("date", date.trim());
        }
        return "redirect:/admin/orders";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * キャンセル理由を整える。
     *
     * <p>空欄なら既定の文言を入れます（理由が空の伝票は後から見て困るため）。
     * また DB の列は 100 文字までなので、超える分は切り詰めます。
     * 画面側にも {@code maxlength} を付けていますが、
     * <b>画面のチェックは簡単にすり抜けられる</b>ので、サーバ側でも必ず確認します。
     */
    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "店舗都合";
        }
        String trimmed = reason.trim();
        return trimmed.length() > REASON_MAX_LENGTH
                ? trimmed.substring(0, REASON_MAX_LENGTH)
                : trimmed;
    }
}
