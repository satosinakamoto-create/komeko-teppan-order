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

    /**
     * その卓で「いま開いている伝票」の <b>ID だけ</b>を取る。
     *
     * <p><b>なぜエンティティではなく ID なのか</b><br>
     * 伝票を書き換える処理は「行ロックを取ってから読む」順序でなければ意味がありません。
     * ところがロックの前にエンティティを読んでしまうと、その<b>古い写し</b>が
     * 永続化コンテキストに居座り、あとからロックを取って読み直しても
     * Hibernate は同じインスタンス（＝古い状態）を返してしまいます。
     * そうなるとロックを取った意味がなく、{@code isOpen()} のチェックが
     * 会計済みの伝票を素通りさせます。
     *
     * <p>スカラー値（ID）だけを取る問い合わせなら、エンティティは 1 件も
     * 永続化コンテキストに載りません。だから「ID を引く → ロックを取る → 読む」
     * の順序を崩さずに書けます。
     *
     * <p>{@code List} で受けて先頭を使うのは、万一 1 卓に開いた伝票が 2 つ
     * できていても落ちないようにするため（{@link #findFirstByDiningTableIdAndStatusOrderByOpenedAtDesc}
     * と同じ考え方）。
     */
    @Query("""
            select s.id from TableSession s
            where s.diningTable.id = :tableId
              and s.status = jp.komeko.order.domain.SessionStatus.OPEN
            order by s.openedAt desc, s.id desc
            """)
    List<Long> findOpenSessionIds(@Param("tableId") Long tableId);

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
     * <p><b>伝票やその注文を書き換える経路は、例外なく「読む前に」これを呼ぶこと。</b><br>
     * このアプリのエンティティには {@code @Version} が無く {@code @DynamicUpdate} も
     * 付けていないので、更新は毎回<b>全カラムを {@code where id = ?} だけで</b>
     * 上書きします。ロックを取らずに読んだ古い写しであとからコミットすると、
     * 自分が触ってもいない {@code closed_at} / {@code closed_by} まで巻き戻り、
     * <b>会計が例外も出さずに消えます</b>（回帰テスト: {@code ConcurrentBillLockTest}）。
     * 「読んでからロック」では手遅れです。先に読んだ古いインスタンスが
     * 永続化コンテキストに残り、ロック後の読み直しでもそれが返るためです。
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

    /**
     * 期間ぶんの、会計済み伝票の合計（月次の売上画面用）。
     *
     * <p><b>戻り値を Object[] にしていない理由</b><br>
     * 上の {@code summarizeClosed} は列が並んだだけの配列を返すので、
     * どの位置が何の金額かを人が覚えておく必要があり、間違えても
     * コンパイラは何も言いません（実際どこからも呼ばれていません）。
     * こちらは名前で読める形（Spring Data の projection）にしてあります。
     *
     * <p>数えるのは<b>閉じた伝票</b>です。注文ではありません。
     * テーブルチャージと深夜料金は伝票にしか乗らないので、
     * 注文の合計を売上と呼ぶと、実際にいただいた金額より小さくなります。
     */
    interface ClosedTotal {
        /** 会計した組数。 */
        Long getBills();
        /** ご請求額の合計（税込・チャージと深夜料金を含む）。 */
        Long getGross();
        /** うち消費税（内税）。 */
        Long getTax();
        /** テーブルチャージの合計。 */
        Long getTableCharge();
        /** 深夜料金の合計。 */
        Long getLateNight();
        /** 客数の合計。 */
        Long getGuests();
    }

    @Query("""
            select count(s) as bills,
                   coalesce(sum(s.totalAmount), 0) as gross,
                   coalesce(sum(s.taxAmount), 0) as tax,
                   coalesce(sum(s.tableChargeAmount), 0) as tableCharge,
                   coalesce(sum(s.lateNightAmount), 0) as lateNight,
                   coalesce(sum(s.guestCount), 0) as guests
            from TableSession s
            where s.businessDate between :from and :to
              and s.status = jp.komeko.order.domain.SessionStatus.CLOSED
            """)
    ClosedTotal summarizeClosedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByStatus(SessionStatus status);
}
