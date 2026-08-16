package jp.komeko.order.repository;

import jp.komeko.order.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * カテゴリの DB アクセス。
 *
 * <p><b>リポジトリとは</b><br>
 * インターフェースを書くだけで、Spring Data JPA が実装クラスを自動生成してくれます。
 * {@code JpaRepository<Category, Long>} を継承した時点で
 * {@code save() / findById() / findAll() / delete()} などが使えるようになります。
 *
 * <p>メソッド名からも SQL が組み立てられます。
 * {@code findByVisibleTrueOrderBySortOrderAsc} は
 * 「visible = true の行を sort_order 昇順で取る」と解釈されます。
 * これを「クエリメソッド」と呼びます。
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** お客さんに見せるカテゴリ（表示 ON のもの）を並び順で取得。 */
    List<Category> findByVisibleTrueOrderBySortOrderAscIdAsc();

    /** 管理画面用。非表示も含めて全件を並び順で取得。 */
    List<Category> findAllByOrderBySortOrderAscIdAsc();
}
