package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.Purchase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 仕入れ・経費の読み書き。
 *
 * <p><b>検索メソッドが電子帳簿保存法の要件そのもの</b><br>
 * スキャナ保存では「取引年月日・取引金額・取引先」で検索できることが求められ、
 * さらに日付と金額は範囲で、2 項目以上を組み合わせて絞り込めることが要ります。
 * {@link #search} の引数がちょうどそれに対応しています。
 * 画面の便利機能ではなく、<b>法律上そこにないと困る機能</b>です。
 *
 * <p><b>削除済みも検索できる必要がある</b><br>
 * 「削除したデータも検索できること」まで要件に含まれるため、
 * 既定では除外しつつ、{@code includeDeleted} で含められるようにしています。
 */
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /**
     * 検索 3 項目による絞り込み。どの引数も null なら条件にしない。
     *
     * @param from           取引日の開始（null 可）
     * @param to             取引日の終了（null 可）
     * @param minAmount      合計金額の下限（null 可）
     * @param maxAmount      合計金額の上限（null 可）
     * @param storeKeyword   取引先名の部分一致（null・空文字なら条件にしない）
     * @param includeDeleted true なら論理削除済みも含める
     */
    @Query("""
            select p from Purchase p
            where (:from is null or p.purchasedOn >= :from)
              and (:to is null or p.purchasedOn <= :to)
              and (:minAmount is null or p.totalAmount >= :minAmount)
              and (:maxAmount is null or p.totalAmount <= :maxAmount)
              and (:storeKeyword is null or lower(p.storeName) like lower(concat('%', :storeKeyword, '%')))
              and (:includeDeleted = true or p.deleted = false)
            order by p.purchasedOn desc, p.id desc
            """)
    Page<Purchase> search(@Param("from") LocalDate from,
                          @Param("to") LocalDate to,
                          @Param("minAmount") Integer minAmount,
                          @Param("maxAmount") Integer maxAmount,
                          @Param("storeKeyword") String storeKeyword,
                          @Param("includeDeleted") boolean includeDeleted,
                          Pageable pageable);

    /**
     * 期間内の仕入れを明細ごと読み込む（月次集計用）。
     *
     * <p>{@code join fetch} で明細をまとめて読むのは、集計のたびに
     * 行数ぶんの SQL が飛ぶのを避けるためです（N+1 問題）。
     * {@code distinct} が要るのは、明細が 3 行ある仕入れが 3 回返ってしまうためです。
     */
    @Query("""
            select distinct p from Purchase p
            left join fetch p.lines
            where p.deleted = false
              and p.purchasedOn between :from and :to
            order by p.purchasedOn asc, p.id asc
            """)
    List<Purchase> findForPeriodWithLines(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 明細つきで 1 件読む（詳細画面用）。 */
    @Query("""
            select p from Purchase p
            left join fetch p.lines
            where p.id = :id
            """)
    Optional<Purchase> findByIdWithLines(@Param("id") Long id);

    /**
     * 税理士に見せる「例外リスト」。
     *
     * <p>登録番号が確認できなかったもの、紙の保管が要るもの、
     * 紙との同等確認がまだのもの。ここに出たものだけ人が見れば済むようにする。
     */
    @Query("""
            select p from Purchase p
            where p.deleted = false
              and p.purchasedOn between :from and :to
              and (p.regVerifyStatus in ('NOT_FOUND', 'REVOKED')
                   or p.paperRetentionRequired = true
                   or p.equivalenceCheckedAt is null)
            order by p.purchasedOn desc, p.id desc
            """)
    List<Purchase> findNeedingAttention(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
