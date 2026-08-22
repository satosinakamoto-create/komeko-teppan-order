package jp.komeko.order.repository;

import jakarta.persistence.LockModeType;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.TableSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 伝票（来店）の DB アクセス。
 */
public interface TableSessionRepository extends JpaRepository<TableSession, Long> {

    /**
     * その卓で「いま開いている伝票」を 1 件取る。
     *
     * <p>本来 1 卓につき開いている伝票は 1 つだけのはずですが、
     * 万一 2 つできてしまっても落ちないよう
     * {@code findFirst...OrderByOpenedAtDesc}（新しいほうを 1 件）にしています。
     *
     * <p><b>ここで {@code orders} を fetch しない理由</b><br>
     * 「1 件だけ取る」と「コレクションをまとめて読む」を同時に指定すると、
     * Hibernate は SQL で件数を絞れず<b>全件読んでからメモリ上で 1 件目を取る</b>
     * 動きになります（警告 HHH90003004）。
     * 注文は {@code TableService} のトランザクションの中で読み込むので、
     * ここでは卓だけ一緒に読みます。
     */
    @EntityGraph(attributePaths = {"diningTable"})
    Optional<TableSession> findFirstByDiningTableIdAndStatusOrderByOpenedAtDesc(
            Long diningTableId, SessionStatus status);

    @EntityGraph(attributePaths = {"diningTable", "orders"})
    Optional<TableSession> findWithOrdersById(Long id);

    /**
     * 伝票の行ロックを取って読む（SELECT … FOR UPDATE）。
     *
     * <p>「お会計で締める」と「お客さまの追加注文」がほぼ同時に走ると、
     * どちらも自分のチェックの時点では矛盾が無いのに、
     * <b>締めた伝票に注文がぶら下がる</b>（＝お客さまには「承りました」と
     * 出たのに誰にも請求されない）ことがあります。
     * 会計と注文確定の双方がまずこのロックを取ることで、2 つの操作を
     * 直列化しています。後から来たほうは、先の操作の結果を見てから動きます。
     *
     * <p>fetch 指定を付けていないのは、ロック用の SELECT を単純に保つためです。
     * 関連は続けて {@link #findWithOrdersById(Long)} で読めば、
     * 同じ永続化コンテキストの同じインスタンスに載ります。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSession s where s.id = :id")
    Optional<TableSession> findWithLockById(@Param("id") Long id);

    /** ホール画面用：いま開いている伝票の一覧。 */
    @EntityGraph(attributePaths = {"diningTable", "orders"})
    List<TableSession> findByStatusOrderByOpenedAtAsc(SessionStatus status);

    /** 管理画面用：その営業日の伝票（新しい順）。 */
    @EntityGraph(attributePaths = {"diningTable"})
    List<TableSession> findByBusinessDateOrderByOpenedAtDesc(LocalDate businessDate);

    /** 会計済みの伝票の合計（売上集計用）。 */
    @Query("""
            select count(s), sum(s.totalAmount), sum(s.taxAmount),
                   sum(s.tableChargeAmount), sum(s.lateNightAmount), sum(s.guestCount)
            from TableSession s
            where s.businessDate = :businessDate and s.status = jp.komeko.order.domain.SessionStatus.CLOSED
            """)
    Object[] summarizeClosed(@Param("businessDate") LocalDate businessDate);

    long countByStatus(SessionStatus status);
}
