package jp.komeko.order.service;

import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.DaySummary;
import jp.komeko.order.service.dto.ItemSales;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 売上集計。
 *
 * <p>集計対象は「受渡済（COMPLETED）」の注文だけです。
 * キャンセルはもちろん、調理中のものも売上には含めません。
 * <b>いつ売上として計上するか</b>は会計上とても大事な決めごとなので、
 * ここに一箇所だけ書いて、他から真似して書かないようにしています。
 */
@Service
public class SalesReportService {

    /** 売上として数える注文の状態。 */
    private static final OrderStatus SALES_STATUS = OrderStatus.COMPLETED;

    private final OrderRepository orderRepository;
    private final TableSessionRepository tableSessionRepository;
    private final ShopSettingService shopSettingService;

    public SalesReportService(OrderRepository orderRepository,
                              TableSessionRepository tableSessionRepository,
                              ShopSettingService shopSettingService) {
        this.orderRepository = orderRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.shopSettingService = shopSettingService;
    }

    /** 指定営業日のサマリ（件数・売上・消費税）。 */
    @Transactional(readOnly = true)
    public DailySales summary(LocalDate businessDate) {
        DailySales result = orderRepository.summarize(businessDate, SALES_STATUS);
        return result == null ? DailySales.empty() : result;
    }

    /** 指定営業日の商品別ランキング。 */
    @Transactional(readOnly = true)
    public List<ItemSales> ranking(LocalDate businessDate) {
        return orderRepository.rankItems(businessDate, SALES_STATUS);
    }

    // ========================================================================
    //  月単位の集計（売上画面・ダッシュボード）
    // ========================================================================

    /**
     * ひと月ぶんのまとめ。
     *
     * <p><b>売上は「閉じた伝票」から取ります。注文の合計ではありません。</b>
     * テーブルチャージ（1 人 450 円）と深夜料金は伝票にしか乗らないので、
     * 注文の合計を売上と呼ぶと、実際にいただいた金額より小さくなります。
     * 在庫側が 2026-08-31 に同じ理由で伝票ベースへ切り替えたのに合わせています。
     *
     * <p>注文数だけは注文（受渡済）から数えます。
     * 「何回ご注文をいただいたか」は伝票の枚数とは別の数字だからです。
     *
     * @param month 対象の月
     * @param bills 会計した組数（平均単価の分母）
     */
    public record MonthlySales(YearMonth month, Integer day, long sales, long tax,
                               long tableCharge, long lateNight,
                               long bills, long guests, long orders) {

        /** ひと組あたりの平均単価。組数が 0 なら 0（0 除算を画面に持ち込まない）。 */
        public long averagePerBill() {
            return bills == 0 ? 0 : sales / bills;
        }

        /** お一人あたりの単価。 */
        public long averagePerGuest() {
            return guests == 0 ? 0 : sales / guests;
        }

    }

    /** ひと月ぶん。 */
    @Transactional(readOnly = true)
    public MonthlySales monthlySummary(YearMonth month) {
        return between(month, null, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * 1 営業日ぶん。ダッシュボード（今日の営業）が使う。
     *
     * <p>形は月次とそろえてあります。<b>ダッシュボードと売上で
     * 「今日の売上」の出どころが違う</b>と、同じ日を見ているのに額が食い違います。
     */
    @Transactional(readOnly = true)
    public MonthlySales daySummary(LocalDate date) {
        return between(YearMonth.from(date), date.getDayOfMonth(), date, date);
    }

    /** 指定日を右端にした、n 日ぶんの推移（グラフ用）。 */
    @Transactional(readOnly = true)
    public List<MonthlySales> dailySeries(LocalDate last, int days) {
        List<MonthlySales> result = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            result.add(daySummary(last.minusDays(i)));
        }
        return result;
    }

    private MonthlySales between(YearMonth month, Integer day, LocalDate from, LocalDate to) {
        TableSessionRepository.ClosedTotal t = tableSessionRepository.summarizeClosedBetween(from, to);
        long orders = orderRepository.countBetween(from, to, SALES_STATUS);
        if (t == null) {
            return new MonthlySales(month, day, 0, 0, 0, 0, 0, 0, orders);
        }
        return new MonthlySales(month, day,
                zero(t.getGross()), zero(t.getTax()),
                zero(t.getTableCharge()), zero(t.getLateNight()),
                zero(t.getBills()), zero(t.getGuests()), orders);
    }

    /**
     * 指定の月を最後尾にした、n か月ぶんの推移（グラフ用）。
     *
     * <p>月ごとに 1 回ずつ問い合わせます。最長 12 か月なので 12 回。
     * SQL 1 本で月ごとに group by する手もありますが、
     * 月の切り出し方（{@code date_trunc} / {@code strftime} / {@code to_char}）が
     * DB 製品ごとに違い、H2 と PostgreSQL の両方で動く書き方を探すことになります。
     * ここは呼ばれる頻度が低い画面なので、<b>移植性のほうを取っています</b>。
     */
    @Transactional(readOnly = true)
    public List<MonthlySales> monthlySeries(YearMonth last, int months) {
        List<MonthlySales> result = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            result.add(monthlySummary(last.minusMonths(i)));
        }
        return result;
    }

    /** ひと月ぶんの商品別ランキング。 */
    @Transactional(readOnly = true)
    public List<ItemSales> monthlyRanking(YearMonth month) {
        return orderRepository.rankItemsBetween(month.atDay(1), month.atEndOfMonth(), SALES_STATUS);
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    /** 直近 n 日の売上推移（グラフ用）。データが無い日も 0 で埋める。 */
    @Transactional(readOnly = true)
    public List<DaySummary> recentDays(int days) {
        LocalDate today = shopSettingService.currentBusinessDate();
        LocalDate from = today.minusDays(days - 1L);

        // まず DB から「データがある日」だけを取る
        Map<LocalDate, DaySummary> found = new LinkedHashMap<>();
        for (Object[] row : orderRepository.summarizeRange(from, today, SALES_STATUS)) {
            LocalDate date = (LocalDate) row[0];
            long orders = ((Number) row[1]).longValue();
            long amount = ((Number) row[2]).longValue();
            found.put(date, new DaySummary(date, orders, amount));
        }

        // グラフが歯抜けにならないよう、売上ゼロの日も 0 として並べる
        List<DaySummary> series = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            series.add(found.getOrDefault(d, new DaySummary(d, 0, 0)));
        }
        return series;
    }

    /**
     * 時間帯別の売上（何時に混むかを見る）。
     *
     * <p>DB の関数（HOUR など）は製品ごとに書き方が違うので、
     * ここでは Java 側で集計しています。1 日分なら件数も少なく実用上問題ありません。
     */
    @Transactional(readOnly = true)
    public Map<Integer, Long> hourlyAmount(LocalDate businessDate) {
        Map<Integer, Long> byHour = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) {
            byHour.put(h, 0L);
        }
        for (Order order : orderRepository.findByBusinessDateOrderByOrderNumberDesc(businessDate)) {
            if (order.getStatus() != SALES_STATUS) {
                continue;
            }
            int hour = order.getCreatedAt().getHour();
            byHour.merge(hour, (long) order.getTotalAmount(), Long::sum);
        }
        return byHour;
    }
}
