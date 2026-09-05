package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.Ingredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 食材マスタの出し入れ。 */
public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    /** 使っている食材を並び順で。画面の一覧はこれ。 */
    List<Ingredient> findByActiveTrueOrderBySortOrderAscNameAsc();

    /** 使わなくなったものも含めた全件。管理画面の「すべて表示」用。 */
    List<Ingredient> findAllByOrderBySortOrderAscNameAsc();

    /** 名前で 1 件。登録時の重複チェックに使う。 */
    Optional<Ingredient> findByName(String name);
}
