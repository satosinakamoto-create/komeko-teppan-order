package jp.komeko.order.web.admin;

import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.SalesReportService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.DaySummary;
import jp.komeko.order.service.dto.ItemSales;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 売上の集計画面（管理者のみ）。
 *
 * <p>集計そのものは {@link SalesReportService} が行います。
 * このコントローラの仕事は「どの日を見るか決める」「表示用に整える」の 2 つだけです。
 *
 * <p><b>グラフを外部ライブラリなしで描く</b><br>
 * 店内の PC はインターネットに繋がっていないこともあるため、
 * CDN からグラフライブラリを読み込む作りにはしません。
 * app.css の {@code .bars} 一式（棒グラフ）を使い、棒の高さだけを
 * {@code th:style="'height:○%'"} で指定します。
 * その % の計算は、0 除算やまるめの都合をテンプレートに持ち込まないよう
 * <b>すべて Java 側で済ませて</b>から渡します。
 */
@Controller
@RequestMapping("/admin/sales")
public class AdminSalesController {

    /** 推移グラフに出す日数。 */
    private static final int RECENT_DAYS = 7;

    /**
     * 棒の最大の高さ（%）。
     * {@code .bars__item} の中には棒とラベルが縦に並ぶので、
     * 棒を 100% にするとラベルの分だけ枠からはみ出します。少し余裕を残します。
     */
    private static final double BAR_MAX_PERCENT = 85.0;

    /** 見出し用「2026年8月16日(日)」。曜日を日本語で出すため Locale.JAPAN を指定する。 */
    private static final DateTimeFormatter TITLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN);

    /** グラフの目盛り用「8/16」。 */
    private static final DateTimeFormatter BAR_FORMAT =
            DateTimeFormatter.ofPattern("M/d", Locale.JAPAN);

    private final SalesReportService salesReportService;
    private final ShopSettingService shopSettingService;

    public AdminSalesController(SalesReportService salesReportService,
                                ShopSettingService shopSettingService) {
        this.salesReportService = salesReportService;
        this.shopSettingService = shopSettingService;
    }

    /**
     * 売上画面。
     *
     * <p>{@code @DateTimeFormat(iso = DATE)} を付けると、
     * {@code ?date=2026-08-16} のような文字列を {@link LocalDate} に変換してくれます。
     * これが無いと環境のロケール次第で解釈が変わり、ある PC では動くのに
     * 別の PC では 400 エラー、という再現しづらい不具合になります。
     *
     * <p>{@code required = false} なので未指定なら null。
     * そのときは「いまの営業日」を見ます（深夜営業を考慮した営業日で、暦の今日とは限りません）。
     */
    @GetMapping
    public String sales(@RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        Model model) {

        LocalDate target = (date != null) ? date : shopSettingService.currentBusinessDate();

        DailySales summary = salesReportService.summary(target);
        List<ItemSales> ranking = salesReportService.ranking(target);
        List<DaySummary> series = salesReportService.recentDays(RECENT_DAYS);
        Map<Integer, Long> hourly = salesReportService.hourlyAmount(target);

        model.addAttribute("activeNav", "admin");
        model.addAttribute("summary", summary);
        model.addAttribute("ranking", ranking);
        model.addAttribute("dailyBars", toDailyBars(series));
        model.addAttribute("hourlyBars", toHourlyBars(hourly));

        // 日付は「表示用の日本語」と「URL 用の ISO 文字列」を分けて渡す。
        //   LocalDate をそのままテンプレートに渡して @{...(date=${date})} と書くと、
        //   環境のロケールに合わせて "2026/08/16" のような形に変換されることがあり、
        //   そのリンクを踏んだ瞬間 400 エラーになる。文字列で渡すのがいちばん安全。
        model.addAttribute("dateIso", target.toString());
        model.addAttribute("dateLabel", target.format(TITLE_FORMAT));
        model.addAttribute("prevDateIso", target.minusDays(1).toString());
        model.addAttribute("nextDateIso", target.plusDays(1).toString());
        model.addAttribute("recentDays", RECENT_DAYS);

        return "admin/sales";
    }

    // ========================================================================
    //  表示用の組み立て
    // ========================================================================

    /**
     * 棒グラフ 1 本分のデータ。
     *
     * <p>画面に出すためだけの小さな入れ物なので record にしています。
     * record のアクセサは {@code getLabel()} ではなく {@code label()} なので、
     * テンプレートからは {@code ${b.label()}} のように括弧付きで呼びます。
     *
     * @param label         横軸のラベル（"8/16" や "11"）
     * @param value         金額（円）
     * @param heightPercent 棒の高さ（%）。最大値との比を Java 側で計算済み
     * @param title         マウスを載せたときに出す説明
     */
    public record BarPoint(String label, long value, int heightPercent, String title) {
    }

    /** 直近 n 日の推移を棒グラフ用に整える。 */
    private List<BarPoint> toDailyBars(List<DaySummary> series) {
        long max = 0;
        for (DaySummary d : series) {
            max = Math.max(max, d.amount());
        }

        List<BarPoint> bars = new ArrayList<>();
        for (DaySummary d : series) {
            bars.add(new BarPoint(
                    d.date().format(BAR_FORMAT),
                    d.amount(),
                    percentOf(d.amount(), max),
                    "%s ／ %d件 ／ %,d円".formatted(d.date().format(TITLE_FORMAT), d.orders(), d.amount())));
        }
        return bars;
    }

    /**
     * 時間帯別の売上を棒グラフ用に整える。
     *
     * <p>24 時間ぶんすべて並べるとラベルが潰れるので、
     * 営業時間帯を基本にしつつ、その外側でも売上がある時間は含める、という範囲にします。
     */
    private List<BarPoint> toHourlyBars(Map<Integer, Long> hourly) {
        ShopSetting setting = shopSettingService.currentReadOnly();
        int from = setting.getOpenTime().getHour();
        int to = setting.getCloseTime().getHour();

        for (Map.Entry<Integer, Long> entry : hourly.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                from = Math.min(from, entry.getKey());
                to = Math.max(to, entry.getKey());
            }
        }
        if (to < from) {
            to = from;
        }

        long max = 0;
        for (int h = from; h <= to; h++) {
            max = Math.max(max, valueAt(hourly, h));
        }

        List<BarPoint> bars = new ArrayList<>();
        for (int h = from; h <= to; h++) {
            long amount = valueAt(hourly, h);
            bars.add(new BarPoint(
                    String.valueOf(h),
                    amount,
                    percentOf(amount, max),
                    "%d時台 ／ %,d円".formatted(h, amount)));
        }
        return bars;
    }

    private long valueAt(Map<Integer, Long> hourly, int hour) {
        Long value = hourly.get(hour);
        return value == null ? 0L : value;
    }

    /**
     * 最大値に対する割合（%）。
     *
     * <p><b>ここが 0 除算の分かれ道</b>。
     * 売上がまったく無い日は max が 0 になり、そのまま割ると
     * {@code ArithmeticException: / by zero} で画面ごと落ちます。
     * 「まだ売上が無い」は異常ではなく普通に起きる状態なので、
     * 例外にせず高さ 0% を返します。
     */
    private int percentOf(long value, long max) {
        if (max <= 0 || value <= 0) {
            return 0;
        }
        return (int) Math.round(value * BAR_MAX_PERCENT / max);
    }
}
