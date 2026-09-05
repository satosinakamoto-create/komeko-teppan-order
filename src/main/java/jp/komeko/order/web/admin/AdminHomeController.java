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
        SalesReportService.MonthlySales sales = salesReportService.daySummary(today);
        SalesReportService.MonthlySales prev = salesReportService.daySummary(today.minusDays(1));
        List<SalesReportService.MonthlySales> series = salesReportService.dailySeries(today, RECENT_DAYS);

        model.addAttribute("todayLabel", today.format(DATE_LABEL));
        model.addAttribute("board", board);

        model.addAttribute("sales", sales);
        model.addAttribute("salesDelta", SalesView.deltaPercent(sales.sales(), prev.sales()));
        model.addAttribute("ordersDelta", SalesView.deltaPercent(sales.orders(), prev.orders()));
        model.addAttribute("averageDelta",
                SalesView.deltaPercent(sales.averagePerBill(), prev.averagePerBill()));

        model.addAttribute("chart", SalesView.chart(
                series.stream().map(s -> s.month().getMonthValue() + "/" + s.day()).toList(),
                series.stream().map(SalesReportService.MonthlySales::sales).toList()));
        model.addAttribute("recentDays", RECENT_DAYS);

        model.addAttribute("ranking",
                SalesView.ranking(salesReportService.ranking(today), sales.sales()));

        model.addAttribute("dateIso", today.toString());
        model.addAttribute("prevDateIso", today.minusDays(1).toString());
        model.addAttribute("nextDateIso", today.plusDays(1).toString());
        return "admin/home";
    }

    /** 推移グラフに出す日数。1 週間ぶんあれば「昨日より良いか」も「先週の同じ曜日と比べて」も読める。 */
    private static final int RECENT_DAYS = 7;
}
