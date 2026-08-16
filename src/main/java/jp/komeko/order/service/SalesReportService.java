package jp.komeko.order.service;

import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.DaySummary;
import jp.komeko.order.service.dto.ItemSales;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final ShopSettingService shopSettingService;

    public SalesReportService(OrderRepository orderRepository, ShopSettingService shopSettingService) {
        this.orderRepository = orderRepository;
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
