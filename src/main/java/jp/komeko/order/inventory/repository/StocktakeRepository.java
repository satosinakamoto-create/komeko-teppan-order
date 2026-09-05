package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.Stocktake;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

/**
 * 棚卸し・廃棄・まかないの記録の出し入れ。
 *
 * <p><b>SQL で集計せず、行をまとめて読んで Java で計算します。</b>
 * 食材ごとに「直近の棚卸しはいつか」「そこから先の増減はいくらか」を
 * 別々の SQL で出すと、食材の数だけ往復が増えるうえに、
 * 「対象時点以前の直近を取る」という間違えやすい条件が SQL の中に埋もれます。
 *
 * <p>この規模（食材は数十、記録は年に数百行）なら全部読んでも一瞬です。
 * 既存の {@code PurchaseService.summarize} と同じ判断をしています。
 */
public interface StocktakeRepository extends JpaRepository<Stocktake, Long> {

    /**
     * 指定日までのすべての記録（食材つき、古い順）。在庫計算はこれ 1 本で足りる。
     *
     * <p>古い順に並べているのは、Java 側で「直近の RESET」を
     * 上書きしながら拾っていけるようにするためです。
     */
    @Query("""
            select s from Stocktake s
            join fetch s.ingredient
            where s.takenOn <= :asOf
            order by s.takenOn asc, s.id asc
            """)
    List<Stocktake> findUpTo(LocalDate asOf);

    /** ある食材の記録（新しい順）。食材ごとの履歴表示用。 */
    @Query("""
            select s from Stocktake s
            join fetch s.ingredient
            where s.ingredient.id = :ingredientId
            order by s.takenOn desc, s.id desc
            """)
    List<Stocktake> findHistory(Long ingredientId);

    /** 期間の記録（新しい順）。棚卸し画面の「最近の記録」用。 */
    @Query("""
            select s from Stocktake s
            join fetch s.ingredient
            where s.takenOn between :from and :to
            order by s.takenOn desc, s.id desc
            """)
    List<Stocktake> findForPeriod(LocalDate from, LocalDate to);
}
