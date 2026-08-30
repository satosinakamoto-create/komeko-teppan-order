package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.RecipeLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** レシピ（商品 → 食材と量）の出し入れ。 */
public interface RecipeLineRepository extends JpaRepository<RecipeLine, Long> {

    /**
     * すべてのレシピ行（商品と食材つき）。
     *
     * <p>消費の計算も原価の計算も、レシピ全体を横断して行うので
     * 1 回で読み切ります。商品ごとに問い合わせると、
     * メニュー数だけ往復が増えます（{@code N+1} 問題）。
     */
    @Query("""
            select r from RecipeLine r
            join fetch r.menuItem m
            join fetch r.ingredient
            order by m.sortOrder asc, m.id asc, r.id asc
            """)
    List<RecipeLine> findAllWithRelations();

    /** ある商品のレシピ。編集画面用。 */
    @Query("""
            select r from RecipeLine r
            join fetch r.ingredient
            where r.menuItem.id = :menuItemId
            order by r.id asc
            """)
    List<RecipeLine> findByMenuItem(Long menuItemId);

    /** ある食材を使っている商品。食材を消す前の確認に使う。 */
    @Query("""
            select r from RecipeLine r
            join fetch r.menuItem
            where r.ingredient.id = :ingredientId
            """)
    List<RecipeLine> findByIngredient(Long ingredientId);

    /** レシピが 1 行でも登録されている商品の id。未登録メニューの洗い出しに使う。 */
    @Query("select distinct r.menuItem.id from RecipeLine r")
    List<Long> findMenuItemIdsWithRecipe();

    void deleteByMenuItemId(Long menuItemId);
}
