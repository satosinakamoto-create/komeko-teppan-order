package jp.komeko.order.repository;

import jp.komeko.order.domain.MenuItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 商品の DB アクセス。
 */
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /**
     * お客さん向けメニュー一覧。
     *
     * <p>{@code @EntityGraph} を付けると「この関連も一緒に読んで」と指示できます。
     * これを付けないと、画面でカテゴリ名を表示するたびに 1 件ずつ SELECT が飛び、
     * 商品 30 件なら 31 回 SQL が走ります（有名な <b>N+1 問題</b>）。
     *
     * <p><b>allergens も必ず並べること（ハマりどころ）</b><br>
     * {@code @EntityGraph} は既定で「フェッチグラフ」として扱われ、
     * <b>ここに書かなかった関連は、たとえ {@code FetchType.EAGER} と
     * 宣言していても LAZY 扱いに上書きされます</b>。
     * {@code allergens} を書き忘れると、画面を描くときに
     * {@code LazyInitializationException} が出ます
     * （このアプリは {@code open-in-view: false} で、描画時には DB 接続が無いため）。
     */
    /*
     * ★ 書きかけ（draft）は必ずここでも落とすこと（2026-09-07）。
     *
     *   MenuItem#isOrderable は draft を見ますが、<b>この問い合わせは
     *   isOrderable を通りません</b>。SQL で visible だけを見ていたため、
     *   下書きとして保存した商品がそのままお客さまのメニューに並びました
     *   （実際に出た。価格を入れる前の品が「時価」として出ていた）。
     *
     *   保存側でも visible=false にしていますが、条件は<b>両方</b>に要ります。
     *   片方だけだと、あとから別の経路で下書きが作られたときに素通りします。
     */
    @EntityGraph(attributePaths = {"category", "allergens"})
    @Query("""
            select m from MenuItem m
            where m.visible = true and m.draft = false and m.category.visible = true
            order by m.category.sortOrder asc, m.sortOrder asc, m.id asc
            """)
    List<MenuItem> findVisibleForCustomer();

    /** 管理画面用。非表示も含めて全件。 */
    @EntityGraph(attributePaths = {"category", "allergens"})
    @Query("""
            select m from MenuItem m
            order by m.category.sortOrder asc, m.sortOrder asc, m.id asc
            """)
    List<MenuItem> findAllForAdmin();

    /**
     * 商品詳細（オプショングループまで一緒に読む）。
     *
     * <p>選択肢（OptionChoice）まで一度に JOIN FETCH すると
     * Hibernate が MultipleBagFetchException を投げます
     * （List を 2 段同時に fetch できないため）。
     * 選択肢のほうは {@code @BatchSize} でまとめ読みさせています。
     */
    @Query("""
            select distinct m from MenuItem m
            left join fetch m.optionGroups g
            join fetch m.category
            where m.id = :id
            """)
    Optional<MenuItem> findByIdWithOptions(@Param("id") Long id);

    /**
     * 全商品を、オプションの組まで読んで返す。
     *
     * <p>起動時の一度きりの移行（{@code DataSeeder#backfillToppings}）に使います。
     * ふつうの画面から呼ばないこと。全件をオプションごと載せるので重く、
     * 商品が増えるほど効きます。
     *
     * <p>選択肢まで一度に fetch しない理由は、上と同じ（MultipleBagFetchException）。
     */
    @Query("""
            select distinct m from MenuItem m
            left join fetch m.optionGroups g
            """)
    List<MenuItem> findAllWithOptions();

    /**
     * 残数を「あれば引く」— 在庫管理の心臓部。
     *
     * <p><b>なぜ「読んで、引いて、書き戻す」ではダメなのか</b><br>
     * 残り 1 個の品に 2 人が同時に注文すると、両方が「1 個ある」と読んでしまい、
     * 両方の注文が通って残数が -1 になります（採番のときと同じ競合状態）。
     *
     * <p>そこで<b>条件付き UPDATE</b> を使います。
     * 「残数が足りている行だけを、1 文で引く」を DB にやらせると、
     * DB は同じ行への UPDATE を必ず 1 つずつ順番に処理するので、
     * 2 人同時でも片方は条件を満たさず 0 行更新＝注文失敗になります。
     * 戻り値（更新できた行数）が 1 なら成功、0 なら在庫不足です。
     *
     * <p><b>在庫を管理していない商品（stockRemaining が null）の扱い</b><br>
     * SQL では {@code null - 3} の結果は null のままなので、
     * WHERE で null を許しておけば「無制限の品は何もせず成功」が同じ 1 文で表現できます。
     *
     * <p>{@code @Modifying} は「このクエリは SELECT ではなく更新だ」という宣言。
     * {@code clearAutomatically = true} は、UPDATE が Hibernate のキャッシュを素通りするため、
     * 古い残数を抱えたキャッシュを捨てて次の読み取りを DB に向かわせる指定です。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MenuItem m
            set m.stockRemaining = m.stockRemaining - :qty
            where m.id = :id
              and (m.stockRemaining is null or m.stockRemaining >= :qty)
            """)
    int tryDecrementStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * キャンセルで残数を戻す。
     * 在庫を管理していない商品（null）や、すでに削除された商品は 0 行更新で素通りする。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MenuItem m
            set m.stockRemaining = m.stockRemaining + :qty
            where m.id = :id and m.stockRemaining is not null
            """)
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

    /** カテゴリに属する商品数（カテゴリ削除の可否判定に使う）。 */
    long countByCategoryId(Long categoryId);

    @EntityGraph(attributePaths = {"category", "allergens"})
    List<MenuItem> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);
}
