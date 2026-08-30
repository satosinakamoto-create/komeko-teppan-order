package jp.komeko.order.inventory.repository;

import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 既存の注文データから売上を読むだけのリポジトリ。
 *
 * <p><b>なぜ既存の {@code OrderRepository} を使わないのか</b><br>
 * 在庫モジュールは既存コードに手を入れない、という方針だからです。
 * 既存のリポジトリに集計メソッドを足すと、既存側のファイルを書き換えることになります。
 * JPA は<b>同じエンティティに複数のリポジトリを定義できる</b>ので、
 * こちら側に読み取り専用の入口を自分で用意すれば、既存には指一本触れずに済みます。
 *
 * <p><b>売上として数える範囲は既存の決めごとに合わせる</b><br>
 * {@code SalesReportService} が「受渡済（COMPLETED）だけを売上とする」と決めています。
 * ここで違う数え方をすると、同じ画面に出る 2 つの売上が食い違います。
 * 定義は 1 つ、が会計の鉄則なので、呼び出し側から {@link OrderStatus#COMPLETED} を渡します。
 */
public interface SalesLookupRepository extends JpaRepository<Order, Long> {

    /**
     * 期間の売上（税込）と、そこに含まれる消費税額の合計。
     *
     * <p>税抜売上は「税込 − 税額」で求めます。注文は 1 件ずつ税率を写して持っているので、
     * 期間の途中で税率が変わっても正しく足し合わせられます
     * （店の設定の税率 1 つで割り戻すと、そこがずれる）。
     *
     * <p>戻り値をインタフェースにしているのは、{@code Object[]} だと
     * 「1 行 2 列」なのか「2 行」なのかが呼び出し側から見て曖昧になるためです。
     */
    @Query("""
            select coalesce(sum(o.totalAmount), 0) as grossAmount,
                   coalesce(sum(o.taxAmount), 0)   as taxAmount
            from Order o
            where o.businessDate between :from and :to
              and o.status = :status
            """)
    SalesTotal sumSales(@Param("from") LocalDate from,
                        @Param("to") LocalDate to,
                        @Param("status") OrderStatus status);

    /** 期間の売上合計。{@link #sumSales} の戻り値。 */
    interface SalesTotal {
        /** 税込の売上合計（円）。 */
        Long getGrossAmount();

        /** そこに含まれる消費税の合計（円）。 */
        Long getTaxAmount();
    }
}
