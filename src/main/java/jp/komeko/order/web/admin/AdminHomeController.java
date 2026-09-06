package jp.komeko.order.web.admin;

import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.SalesReportService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.KitchenBoard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 管理画面のダッシュボード（{@code /admin}）。
 *
 * <p><b>この画面の役割</b><br>
 * 店長が朝いちばんに開いて「今日はどうなっている？」を 5 秒で把握するための画面です。
 * 数字は<b>今日の営業日</b>のものだけに絞り、細かい分析は売上画面に任せます。
 *
 * <p><b>アクセス制御はどこに書いてあるか</b><br>
 * このクラスには {@code @PreAuthorize} などを書いていませんが、
 * {@code SecurityConfig} で {@code /admin/**} は ADMIN だけ、と一括で決めてあります。
 * 「URL の形で守る」ほうが書き漏らしが起きにくいためです。
 *
 * <p><b>営業日（businessDate）について</b><br>
 * 深夜営業を考えて、暦の日付ではなく「営業日」で集計します。
 * その判定は {@link ShopSettingService#currentBusinessDate()} が持っているので、
 * 画面側では絶対に {@code LocalDate.now()} を使いません
 * （使うと深夜 2 時に「今日の売上 0 円」になってしまいます）。
 */
@Controller
@RequestMapping("/admin")
public class AdminHomeController {

    /** 画面見出し用の日付フォーマット。スレッドセーフなので使い回してよい。 */
    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPANESE);

    private final OrderService orderService;
    private final SalesReportService salesReportService;
    private final ShopSettingService shopSettingService;

    /**
     * コンストラクタインジェクション。
     * Spring が必要なサービスを自動で渡してくれます（DI）。
     * フィールドを final にできるので「途中ですり替わらない」ことが保証できます。
     */
    public AdminHomeController(OrderService orderService,
                               SalesReportService salesReportService,
                               ShopSettingService shopSettingService) {
        this.orderService = orderService;
        this.salesReportService = salesReportService;
        this.shopSettingService = shopSettingService;
    }

    /**
     * ダッシュボード。
     *
     * <p>{@code model} に入れた値が、そのまま Thymeleaf の {@code ${...}} で読めます。
     * 店舗設定（{@code ${shop}}）だけは {@code GlobalModelAttributes} が
     * 全画面に自動で入れてくれるので、ここでは追加していません。
     */
    @GetMapping
    public String home(Model model) {
        LocalDate today = shopSettingService.currentBusinessDate();

        // 厨房で作業中の 3 レーン（受付／調理中／お渡し可）
        KitchenBoard board = orderService.kitchenBoard();

        // ★ 売上画面と同じ形にする（2026-09-05）★
        //   売上は「月」を見る画面、ダッシュボードは「今日」を見る画面で、
        //   見るものは同じ（総売上・注文数・平均単価・推移・売れている品）。
        //   同じ形にしておけば、店主は片方の読み方を覚えるだけで両方読めます。
        //
        //   数字の出どころも売上画面とそろえて、閉じた伝票から取ります。
        //   注文の合計にはテーブルチャージと深夜料金が乗らないので、
        //   ここだけ注文ベースにすると「ダッシュボードと売上で今日の額が違う」ことになります。
        //
        // ★ 比べる相手は「先週の同じ曜日」（設計 01 ダッシュボード 15:319。2026-09-07）★
        //   もとは前日比だったが、居酒屋の売上は曜日でまるごと変わる。
        //   月曜を日曜と比べると毎週月曜に「大きく落ちた」と出て、
        //   数字が店の調子ではなく曜日を語ってしまう。
        SalesReportService.MonthlySales sales = salesReportService.daySummary(today);
        SalesReportService.MonthlySales prev = salesReportService.daySummary(today.minusWeeks(1));

        model.addAttribute("todayLabel", today.format(DATE_LABEL));
        model.addAttribute("board", board);

        model.addAttribute("sales", sales);
        model.addAttribute("salesDelta", SalesView.deltaPercent(sales.sales(), prev.sales()));
        // 注文数は % ではなく件数の差で出す（設計どおり）。
        // 12 件 → 14 件を「＋16.7%」と書かれても現場ではピンとこない
        model.addAttribute("ordersDiff", sales.orders() - prev.orders());
        model.addAttribute("averageDelta",
                SalesView.deltaPercent(sales.averagePerBill(), prev.averagePerBill()));

        // 推移は「直近 7 日」ではなく「今日の時間帯別」（設計どおり）。
        // 朝いちばんに見る画面なので、昨日までの流れより今日の山谷が要る。
        // 週の流れは売上画面の折れ線（期間切り替え）が受け持つ
        var shop = shopSettingService.currentReadOnly();
        model.addAttribute("chart", SalesView.hourlyChart(
                salesReportService.hourlyAmount(today),
                shop.getOpenTime().getHour(),
                shop.getCloseTime().getHour(),
                shop.getBusinessDayCutoverHour()));

        model.addAttribute("ranking",
                SalesView.ranking(salesReportService.ranking(today), sales.sales()));

        model.addAttribute("dateIso", today.toString());
        model.addAttribute("prevDateIso", today.minusDays(1).toString());
        model.addAttribute("nextDateIso", today.plusDays(1).toString());
        return "admin/home";
    }
}
