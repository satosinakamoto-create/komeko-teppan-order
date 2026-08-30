package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.PurchaseLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

/**
 * 仕入れ明細を「在庫の側から」読むための入口。
 *
 * <p>{@code PurchaseRepository} はレシート 1 枚を単位に扱いますが、
 * 在庫の計算では「どの食材が、いつ、いくつ入ってきたか」を横断で見たいので、
 * 明細を直接引く口を別に用意しています。
 *
 * <p><b>論理削除した仕入れは必ず除きます。</b>
 * 削除済みも検索できることは電子帳簿保存法の要件ですが、
 * それは<b>帳簿を探すとき</b>の話で、在庫に積んではいけません。
 * ここを間違えると、消したはずのレシートの分だけ在庫が水増しされます。
 */
public interface PurchaseLineRepository extends JpaRepository<PurchaseLine, Long> {

    /**
     * 食材が紐付いている明細を、指定日まで全部（古い順）。
     *
     * <p>在庫の入庫も、単価の算出（最新の仕入価格）も、これ 1 本でまかないます。
     * 食材ごとに問い合わせを分けないのは、食材の数だけ往復が増えるためです。
     */
    @Query("""
            select l from PurchaseLine l
            join fetch l.purchase p
            join fetch l.ingredient
            where l.ingredient is not null
              and p.deleted = false
              and p.purchasedOn <= :asOf
            order by p.purchasedOn asc, l.id asc
            """)
    List<PurchaseLine> findStockFeedingLinesUpTo(LocalDate asOf);

    /**
     * 食材に紐付いているのに量が分からず、在庫に積めていない明細。
     *
     * <p>「教えれば在庫が正しくなる」宿題の一覧です。
     * 黙って無視すると在庫がじわじわ実態からずれるので、画面に出します。
     */
    @Query("""
            select l from PurchaseLine l
            join fetch l.purchase p
            join fetch l.ingredient
            where l.ingredient is not null
              and (l.stockQty is null or l.stockQty = 0)
              and p.deleted = false
            order by p.purchasedOn desc, l.id desc
            """)
    List<PurchaseLine> findNeedingQuantityLearning();
}
