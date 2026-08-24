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
import java.time.LocalDateTime;
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
     * この注文がぶら下がっている伝票の <b>ID だけ</b>を引く。
     *
     * <p>注文の状態を書き換える処理は、まず伝票の行ロックを取って
     * 他の操作と直列化します（{@code TableSessionRepository#findWithLockById}）。
     * ところがロックを取るには伝票 ID が要り、その ID は注文が持っています。
     * ここで注文を<b>エンティティとして</b>読んでしまうと、
     * ロックを取る前の古い写しが永続化コンテキストに残り、
     * ロック後に読み直しても同じ（古い）インスタンスが返ってきます。
     * その結果「すでにキャンセル済みか」の判定が古い状態で行われ、
     * 在庫の二重復元やキャンセルの取り消しが起こります。
     *
     * <p>スカラー値だけを取る問い合わせならエンティティは載らないので、
     * 「伝票 ID を引く → ロックを取る → 注文を読む」の順序を保てます。
     */
    @Query("select o.session.id from Order o where o.id = :id")
    Optional<Long> findSessionIdById(@Param("id") Long id);

    /** お客さん専用 URL のトークンから、伝票の ID だけを引く（用途は {@link #findSessionIdById} と同じ）。 */
    @Query("select o.session.id from Order o where o.publicToken = :publicToken")
    Optional<Long> findSessionIdByPublicToken(@Param("publicToken") String publicToken);

    /**
     * 厨房ボード用。指定した状態の注文を、受付が古い順に取得する。
     * 「先に来たお客さんから焼く」ため createdAt 昇順が業務的に正しい並び。
     *
     * <p><b>営業日だけで絞れない理由</b><br>
     * 注文の営業日は伝票の値をコピーする（深夜 0 時をまたいでも同じ伝票のまま
     * にするため。{@code OrderService#placeOrder}）。すると「今夜だけ朝までやる」
     * （alwaysOpen）営業で 5:00（営業日の切り替え）をまたいだ卓の注文は、
     * <b>前営業日の日付</b>を持ったまま厨房に残る。営業日だけで絞ると、この注文は
     * 請求はされるのに厨房ボードのどのレーンにも出ない
     * ＝厨房が存在を知り得ない、という事故になる（2026-08-22 のレビューで発覚）。
     *
     * <p><b>「伝票が OPEN なら出す」では足りなかった</b><br>
     * 最初の修正は営業日に加えて「開いている伝票の注文」を出す形だった。
     * しかし<b>会計と調理は独立している</b>（{@code TableService#closeSession} には
     * 未提供注文のガードが無い。先にレジを済ませて料理を待つ卓は普通にある）。
     * 4:50 に会計を締めた卓に焼き待ちの注文が残ったまま 5:00 をまたぐと、
     * その注文は「前営業日 <b>かつ</b> CLOSED」になり、どちらの条件にも当たらず
     * 全レーンから消える。請求は済んでいるのでお金は消えないが、
     * <b>厨房が焼くべき品の存在を知り得なくなる</b>のは同型の事故（2026-08-25 のレビュー）。
     *
     * <p><b>だから伝票の状態は見ず、受付時刻で線を引く。</b><br>
     * 未提供（RECEIVED/COOKING/READY）の注文は、営業日と伝票の状態にかかわらず
     * {@code since} 以降に受け付けたものを出す。線をどこに置くかと、その理由は
     * {@code OrderService#CARRY_OVER_WINDOW} に書いてある。
     *
     * @param businessDate いまの営業日。この日の注文は時刻に関係なく必ず出す
     * @param since        営業日が違う注文を拾う下限（これより古い焼き忘れは出さない）
     */
    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    @Query("""
            select o from Order o
            where o.status in :statuses
              and (o.businessDate = :businessDate or o.createdAt >= :since)
            order by o.createdAt asc
            """)
    List<Order> findKitchenBoardOrders(
            @Param("businessDate") LocalDate businessDate,
            @Param("since") LocalDateTime since,
            @Param("statuses") Collection<OrderStatus> statuses);

    /** 待ち組数のカウント。 */
    long countByBusinessDateAndStatusIn(LocalDate businessDate, Collection<OrderStatus> statuses);

    /** その営業日の全注文（管理画面の一覧用）。新しい順。 */
    @EntityGraph(attributePaths = {"lines", "session", "session.diningTable"})
    List<Order> findByBusinessDateOrderByOrderNumberDesc(LocalDate businessDate);

    Optional<Order> findByBusinessDateAndOrderNumber(LocalDate businessDate, int orderNumber);

    /**
     * まだ厨房に残っている注文のうち、指定時刻より前に受け付けられたものの件数。
     * お客さんの画面に「あと ◯ 組でお呼び出しです」と出すために使う。
     *
     * <p><b>絞り込みは {@link #findKitchenBoardOrders} と必ず同じにすること。</b><br>
     * ここだけ「営業日の厳密一致」にしておくと、5:00 をまたいだ卓の注文と
     * 新しい営業日の注文が互いを「前にいる組」として数えず、
     * <b>厨房ボードの並びとお客さま画面の「あと ◯ 組」が食い違う</b>。
     * 実際に鉄板を占領しているのは、ボードに出ている注文のほうである。
     */
    @Query("""
            select count(o) from Order o
            where o.status in :statuses
              and (o.businessDate = :businessDate or o.createdAt >= :since)
              and o.createdAt < :before
            """)
    long countAheadOf(@Param("businessDate") LocalDate businessDate,
                      @Param("since") LocalDateTime since,
                      @Param("statuses") Collection<OrderStatus> statuses,
                      @Param("before") LocalDateTime before);

    /**
     * 自分より前に並んでいる注文の調理時間の合計（分）。
     * 待ち時間の目安を出すのに使う。絞り込みは {@link #countAheadOf} と同じ。
     */
    @Query("""
            select sum(o.estimatedCookMinutes) from Order o
            where o.status in :statuses
              and (o.businessDate = :businessDate or o.createdAt >= :since)
              and o.createdAt < :before
            """)
    Long sumCookMinutesAheadOf(@Param("businessDate") LocalDate businessDate,
                               @Param("since") LocalDateTime since,
                               @Param("statuses") Collection<OrderStatus> statuses,
                               @Param("before") LocalDateTime before);

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
