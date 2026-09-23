package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.RecipeOtherCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * その他材料費の出し入れ。
 *
 * <p>商品 1 つにつき 1 件なので、引くときは必ず {@code menuItemId} から。
 * 一覧（原価表）は商品ぶんまとめて 1 回で読みます。
 * 商品が 80 あるときに 80 回引くと、原価表を開くたびに往復が増えるためです。
 */
public interface RecipeOtherCostRepository extends JpaRepository<RecipeOtherCost, Long> {

    Optional<RecipeOtherCost> findByMenuItemId(Long menuItemId);

    /** 原価表用。全件読んで Java 側で商品 id にマップする。 */
    List<RecipeOtherCost> findAll();

    /**
     * 商品を消すときの後片付け。
     *
     * <p>外部キーに {@code ON DELETE CASCADE} を付けていないので、
     * 商品より先にこちらを消さないと削除が外部キー違反で失敗します
     * （{@code MenuService.deleteItem} が呼びます）。
     */
    void deleteByMenuItemId(Long menuItemId);
}
