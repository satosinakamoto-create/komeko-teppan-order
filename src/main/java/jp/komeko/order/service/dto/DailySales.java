package jp.komeko.order.service.dto;

/**
 * 1 営業日の売上サマリ。
 *
 * <p>集計 SQL の結果を受け取る器なので record（不変の値オブジェクト）にしています。
 * SUM は対象行が 0 件だと null になり得るため、ラッパー型の {@link Long} で受けて
 * getter 側で 0 に丸めています。
 */
public record DailySales(Long orderCount, Long totalAmount, Long taxAmount) {

    public static DailySales empty() {
        return new DailySales(0L, 0L, 0L);
    }

    public long orders() {
        return orderCount == null ? 0L : orderCount;
    }

    public long total() {
        return totalAmount == null ? 0L : totalAmount;
    }

    public long tax() {
        return taxAmount == null ? 0L : taxAmount;
    }

    /** 税抜相当（表示用）。 */
    public long net() {
        return total() - tax();
    }

    /** 客単価（円）。0 件のときは 0。 */
    public long averagePerOrder() {
        long n = orders();
        return n == 0 ? 0 : total() / n;
    }
}
