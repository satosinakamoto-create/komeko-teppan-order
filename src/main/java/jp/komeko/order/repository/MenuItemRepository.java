package jp.komeko.order.repository;

import jp.komeko.order.domain.MenuItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            select m from MenuItem m
            where m.visible = true and m.category.visible = true
            order by m.category.sortOrder asc, m.sortOrder asc, m.id asc
            """)
    List<MenuItem> findVisibleForCustomer();

    /** 管理画面用。非表示も含めて全件。 */
    @EntityGraph(attributePaths = {"category"})
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

    /** カテゴリに属する商品数（カテゴリ削除の可否判定に使う）。 */
    long countByCategoryId(Long categoryId);

    @EntityGraph(attributePaths = {"category"})
    List<MenuItem> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);
}
