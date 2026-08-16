package jp.komeko.order.repository;

import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.ItemSales;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 注文の DB アクセス。
 *
 * <p>{@code @EntityGraph(attributePaths = "lines")} を付けたメソッドは
 * 明細も一緒に読み込みます。このアプリは {@code open-in-view: false} なので、
 * 画面を描くときには「必要なものを読み終えた状態」で返す必要があるためです。
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** お客さん専用 URL からの取得。 */
    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    Optional<Order> findByPublicToken(String publicToken);

    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    Optional<Order> findWithLinesById(Long id);

    /**
     * 厨房ボード用。指定した状態の注文を、受付が古い順に取得する。
     * 「先に来たお客さんから焼く」ため createdAt 昇順が業務的に正しい並び。
     */
    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    List<Order> findByBusinessDateAndStatusInOrderByCreatedAtAsc(
            LocalDate businessDate, Collection<OrderStatus> statuses);

    /** サイネージ用。番号だけ使うので明細は読まない（軽くする）。 */
    List<Order> findByBusinessDateAndStatusOrderByOrderNumberAsc(
            LocalDate businessDate, OrderStatus status);

    /** 待ち組数のカウント。 */
    long countByBusinessDateAndStatusIn(LocalDate businessDate, Collection<OrderStatus> statuses);

    /** その営業日の全注文（管理画面の一覧用）。新しい順。 */
    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    List<Order> findByBusinessDateOrderByOrderNumberDesc(LocalDate businessDate);

    Optional<Order> findByBusinessDateAndOrderNumber(LocalDate businessDate, int orderNumber);

    /**
     * まだ厨房に残っている注文のうち、指定時刻より前に受け付けられたものの件数。
     * お客さんの画面に「あと ◯ 組でお呼び出しです」と出すために使う。
     */
    @Query("""
            select count(o) from Order o
            where o.businessDate = :businessDate
              and o.status in :statuses
              and o.createdAt < :before
            """)
    long countAheadOf(@Param("businessDate") LocalDate businessDate,
                      @Param("statuses") Collection<OrderStatus> statuses,
                      @Param("before") java.time.LocalDateTime before);

    /**
     * 自分より前に並んでいる注文の調理時間の合計（分）。
     * 待ち時間の目安を出すのに使う。
     */
    @Query("""
            select sum(o.estimatedCookMinutes) from Order o
            where o.businessDate = :businessDate
              and o.status in :statuses
              and o.createdAt < :before
            """)
    Long sumCookMinutesAheadOf(@Param("businessDate") LocalDate businessDate,
                               @Param("statuses") Collection<OrderStatus> statuses,
                               @Param("before") java.time.LocalDateTime before);

    /**
     * 日次の売上サマリ。
     *
     * <p>{@code select new ...(...)} は「コンストラクタ式」といって、
     * 検索結果をそのまま自作クラス（ここでは record）に詰めて返す書き方です。
     * エンティティを丸ごと読むより速く、必要な列だけ取れます。
     *
     * <p>{@code coalesce(sum(...), 0)} で 0 に丸めたくなるところですが、
     * それをすると型が Integer と Long で食い違い、
     * record のコンストラクタが見つからないというエラーになります。
     * 対象 0 件のとき SUM は null になるので、
     * <b>null のまま受けて Java 側（{@code DailySales#total()}）で 0 に丸めています。</b>
     */
    @Query("""
            select new jp.komeko.order.service.dto.DailySales(
                count(o), sum(o.totalAmount), sum(o.taxAmount))
            from Order o
            where o.businessDate = :businessDate and o.status = :status
            """)
    DailySales summarize(@Param("businessDate") LocalDate businessDate,
                         @Param("status") OrderStatus status);

    /** 商品別の売れ筋ランキング。 */
    @Query("""
            select new jp.komeko.order.service.dto.ItemSales(
                l.menuItemName, sum(l.quantity), sum(l.lineTotal))
            from OrderLine l
            join l.order o
            where o.businessDate = :businessDate and o.status = :status
            group by l.menuItemName
            order by sum(l.lineTotal) desc, sum(l.quantity) desc
            """)
    List<ItemSales> rankItems(@Param("businessDate") LocalDate businessDate,
                              @Param("status") OrderStatus status);

    /**
     * 期間集計（週次・月次のグラフ用）。営業日ごとの売上。
     * {@code group by} があるので各行は必ず 1 件以上あり、SUM が null になることはない。
     */
    @Query("""
            select o.businessDate, count(o), sum(o.totalAmount)
            from Order o
            where o.businessDate between :from and :to and o.status = :status
            group by o.businessDate
            order by o.businessDate asc
            """)
    List<Object[]> summarizeRange(@Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("status") OrderStatus status);
}
