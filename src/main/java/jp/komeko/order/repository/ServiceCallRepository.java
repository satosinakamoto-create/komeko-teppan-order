package jp.komeko.order.repository;

import jp.komeko.order.domain.ServiceCall;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * お客さまからの呼び出しの読み書き。
 */
public interface ServiceCallRepository extends JpaRepository<ServiceCall, Long> {

    /**
     * まだ対応していない呼び出し。古い順（先に呼んだ卓から向かう）。
     *
     * <p><b>営業日で絞らない理由</b><br>
     * 厨房ボードと同じ考え方です（{@code OrderRepository#findKitchenBoardOrders}）。
     * 呼び出しの営業日は伝票からコピーするので、5:00 の切り替えをまたいだ卓の
     * 呼び出しは前営業日のまま残ります。営業日で絞ると、
     * <b>まだ席にいるお客さまの呼び出しが画面から消えます</b>。
     * 呼ばれたことが誰にも分からなくなるのは、いちばん避けたい壊れ方です。
     *
     * <p>代わりに「いつ呼ばれたか」で線を引きます。
     * 前の日の押し忘れが翌日ずっと居座らないための下限です。
     *
     * <p>{@code EntityGraph} で卓まで読んでおくのは、
     * {@code open-in-view: false} のため画面では遅延読み込みができないからです。
     */
    @EntityGraph(attributePaths = {"session", "session.diningTable"})
    @Query("""
            select c from ServiceCall c
            where c.handledAt is null and c.createdAt >= :since
            order by c.createdAt asc
            """)
    List<ServiceCall> findPending(@Param("since") LocalDateTime since);

    /**
     * まだ対応していない呼び出しの<b>件数だけ</b>。
     *
     * <p>ヘッダーの見出しに使います。一覧を読んで数えると、
     * 全画面で毎回まるごと載せることになります。
     */
    @Query("""
            select count(c) from ServiceCall c
            where c.handledAt is null and c.createdAt >= :since
            """)
    long countPending(@Param("since") LocalDateTime since);
}
