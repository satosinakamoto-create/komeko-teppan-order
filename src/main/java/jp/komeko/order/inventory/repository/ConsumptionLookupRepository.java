package jp.komeko.order.inventory.repository;

import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 「何がいくつ売れたか」を既存の注文データから読むだけの入口。
 *
 * <p><b>読むだけです。書きません。</b>
 * 既存の {@code OrderRepository} に問い合わせを足せば済む話ですが、
 * それは既存ファイルの変更になります。在庫モジュールの都合を既存側に
 * 持ち込まないため、こちら側に別の口を用意しています
 * （{@code SalesLookupRepository} と同じ考え方）。
 *
 * <p><b>キャンセルした注文は除きます。</b>
 * 会計から外す判定と同じものを使います。作らなかった料理の材料は減りません。
 * ここで別の判定を書くと、「請求からは外れたのに在庫は減っている」という
 * 説明のつかない状態が生まれます。
 *
 * <p>調理を始めていれば材料は減っているので、{@code COMPLETED} だけに絞らず
 * <b>キャンセル以外すべて</b>を数えます。売上の集計（受渡済みのみ）とは
 * 意図的に条件を変えています。お金と材料は減るタイミングが違うからです。
 */
public interface ConsumptionLookupRepository extends JpaRepository<Order, Long> {

    /**
     * 期間に売れた商品の数を、商品ごとに合計する。
     *
     * @param fromExclusive この日は<b>含めない</b>（棚卸しの日はすでに実測に含まれているため）。
     *                     null なら期間の下限なし
     * @param to            この日まで（含む）
     */
    @Query("""
            select ol.menuItemId as menuItemId, coalesce(sum(ol.quantity), 0) as quantity
            from Order o join o.lines ol
            where (:fromExclusive is null or o.businessDate > :fromExclusive)
              and o.businessDate <= :to
              and o.status <> :excluded
              and ol.menuItemId is not null
            group by ol.menuItemId
            """)
    List<SoldQuantity> sumSoldByMenuItem(@Param("fromExclusive") LocalDate fromExclusive,
                                         @Param("to") LocalDate to,
                                         @Param("excluded") OrderStatus excluded);

    /**
     * 期間のうち「注文が 1 件以上あった日」の数。
     *
     * <p><b>これが営業日数です。</b>営業日をカレンダーや設定から決めず、
     * 実績から数えます。水木定休も、臨時休業も、貸切も、自動的に除かれます。
     * 「設定した営業日」と「実際に営業した日」がずれる心配がありません。
     */
    @Query("""
            select count(distinct o.businessDate)
            from Order o
            where o.businessDate between :from and :to
              and o.status <> :excluded
            """)
    long countBusinessDays(@Param("from") LocalDate from,
                           @Param("to") LocalDate to,
                           @Param("excluded") OrderStatus excluded);

    /** 商品ごとの販売数。 */
    interface SoldQuantity {
        Long getMenuItemId();

        Long getQuantity();
    }
}
