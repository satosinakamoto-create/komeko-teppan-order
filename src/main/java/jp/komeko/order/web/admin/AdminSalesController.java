package jp.komeko.order.web.admin;

import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.SalesReportService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
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
 * <p><b>数字が 2 種類あることに注意</b><br>
 * イートインでは<b>お会計の単位は伝票（{@link TableSession}）</b>です。
 * ところが {@link SalesReportService} は「受渡済の注文の合計」を売上とみなす作りなので、
 * テーブルチャージと深夜料金が入りません。
 * どちらか一方を消すのではなく、
 * <b>「伝票ベース（実際にいただいた金額）」と「注文ベース（商品の売れ行き）」を並べて出し、
 * それぞれ何の数字かを画面に明記する</b>方針にしています。
 * 数字が 2 つあること自体より、どちらが何なのか分からないことのほうが現場は混乱します。
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
    private final TableService tableService;

    public AdminSalesController(SalesReportService salesReportService,
                                ShopSettingService shopSettingService,
                                TableService tableService) {
        this.salesReportService = salesReportService;
        this.shopSettingService = shopSettingService;
        this.tableService = tableService;
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

        // 伝票（＝実際にいただいた金額）の集計。
        // TableService#sessionsOf は
        // TableSessionRepository#findByBusinessDateOrderByOpenedAtDesc を呼ぶだけの薄い窓口です。
        // 卓（diningTable）まで読み終えた状態で返ってくるので、
        // open-in-view: false でも画面で困りません（今回は金額しか使いませんが）。
        List<TableSession> sessions = tableService.sessionsOf(target);

        model.addAttribute("summary", summary);
        // モデル名に "session" は使えない（Thymeleaf では HttpSession を指す予約語とぶつかる）ので
        // 伝票の集計は billSummary という名前で渡す。
        model.addAttribute("billSummary", summarizeBills(sessions));
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
    //  伝票（お会計）ベースの集計
    // ========================================================================

    /**
     * 1 営業日ぶんの「伝票ベース」の集計結果。
     *
     * <p>画面に出すためだけの入れ物なので record にしています。
     * record のアクセサは {@code getBills()} ではなく {@code bills()} なので、
     * テンプレートからは {@code ${billSummary.bills()}} のように括弧付きで呼びます。
     *
     * <p><b>金額を自分で足し算しているのは「伝票が持っている確定値」だけ</b>です。
     * 小計・チャージ・深夜料金の<b>計算式は {@code TableSession#recalculate} にしかありません</b>。
     * ここで割合を掛けたり足す順番を変えたりすると、
     * 画面の数字とレシートの数字が食い違う（＝金銭事故）ので絶対にしないでください。
     *
     * @param bills       会計済みの伝票の数（＝組数）
     * @param openBills   まだ会計していない伝票の数（この集計には入っていない）
     * @param guests      お客さまの人数の合計
     * @param total       ご請求額の合計
     * @param subtotal    小計（注文ぶん）の合計
     * @param tableCharge テーブルチャージの合計
     * @param lateNight   深夜料金の合計
     * @param tax         ご請求額に含まれる消費税の合計
     */
    public record BillSummary(long bills, long openBills, long guests, long total,
                              long subtotal, long tableCharge, long lateNight, long tax) {

        /** 客単価（お一人あたり）。人数 0 のときは 0（0 で割らない）。 */
        public long averagePerGuest() {
            return guests == 0 ? 0 : total / guests;
        }

        /** 組単価（1 伝票あたり）。0 組のときは 0。 */
        public long averagePerBill() {
            return bills == 0 ? 0 : total / bills;
        }
    }

    /**
     * その営業日の伝票を「会計済みのものだけ」集計する。
     *
     * <p>まだお会計していない伝票（OPEN）を混ぜてはいけません。
     * 金額が途中経過のうえ、そのあと追加注文が入れば変わるからです。
     * ただし「何組ぶんがまだ入っていないのか」は店長が知りたい情報なので、
     * 件数だけ数えて画面に出しています。
     *
     * <p>リポジトリには {@code summarizeClosed} という集計クエリもありますが、
     * 戻り値が {@code Object[]}（列が並んだだけの配列）で、
     * どの位置が何の金額かを人が覚えておかなければならず間違えやすいので使っていません。
     * 1 営業日ぶんの伝票はせいぜい数十件なので、Java 側で数えれば十分です。
     */
    private BillSummary summarizeBills(List<TableSession> sessions) {
        long bills = 0;
        long openBills = 0;
        long guests = 0;
        long total = 0;
        long subtotal = 0;
        long tableCharge = 0;
        long lateNight = 0;
        long tax = 0;

        for (TableSession bill : sessions) {
            if (bill.getStatus() != SessionStatus.CLOSED) {
                openBills++;
                continue;
            }
            bills++;
            guests += bill.getGuestCount();
            total += bill.getTotalAmount();
            subtotal += bill.getSubtotalAmount();
            tableCharge += bill.getTableChargeAmount();
            lateNight += bill.getLateNightAmount();
            tax += bill.getTaxAmount();
        }
        return new BillSummary(bills, openBills, guests, total, subtotal, tableCharge, lateNight, tax);
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
     *
     * <p><b>「何時」ではなく「営業日の何番目の時間帯か」で並べる</b><br>
     * 深夜営業の店では、同じ営業日の中に 23 時と翌 0 時が同居します。
     * 単純に時刻の小さい順（0,1,…,23）に並べると、
     * <b>いちばん遅い時間帯の棒がグラフのいちばん左に来てしまい</b>、
     * 時間の流れが読めないグラフになります。
     * そこで、営業日の切り替え時刻（{@code businessDayCutoverHour}）を 0 番目とする
     * 通し位置に直してから並べます。
     * 深夜営業をしない店では、切り替え時刻をまたがないので並びは今までどおりです。
     */
    private List<BarPoint> toHourlyBars(Map<Integer, Long> hourly) {
        ShopSetting setting = shopSettingService.currentReadOnly();
        int cutover = setting.getBusinessDayCutoverHour();

        int from = positionOf(setting.getOpenTime().getHour(), cutover);
        int to = positionOf(setting.getCloseTime().getHour(), cutover);

        for (Map.Entry<Integer, Long> entry : hourly.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                int position = positionOf(entry.getKey(), cutover);
                from = Math.min(from, position);
                to = Math.max(to, position);
            }
        }
        if (to < from) {
            to = from;
        }

        long max = 0;
        for (int p = from; p <= to; p++) {
            max = Math.max(max, valueAt(hourly, hourAt(p, cutover)));
        }

        List<BarPoint> bars = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            int hour = hourAt(p, cutover);
            long amount = valueAt(hourly, hour);
            bars.add(new BarPoint(
                    String.valueOf(hour),
                    amount,
                    percentOf(amount, max),
                    "%d時台 ／ %,d円".formatted(hour, amount)));
        }
        return bars;
    }

    /**
     * 時刻（0〜23）を「営業日の何番目の時間帯か」（0〜23）に直す。
     * {@code Math.floorMod} を使うのは、引き算がマイナスになっても
     * 0〜23 に収めたいためです（{@code %} だとマイナスがそのまま残ります）。
     */
    private static int positionOf(int hour, int cutoverHour) {
        return Math.floorMod(hour - cutoverHour, 24);
    }

    /** {@link #positionOf} の逆変換。通し位置を実際の時刻に戻す。 */
    private static int hourAt(int position, int cutoverHour) {
        return Math.floorMod(cutoverHour + position, 24);
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
