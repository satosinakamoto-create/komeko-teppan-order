package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.ItemAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 入り数の記憶の出し入れ。
 *
 * <p>探すときは必ず {@code AliasText.normalize} を通した文字列で引くこと。
 * 生の品名で引くと、半角カナのレシートが永遠に見つかりません。
 */
public interface ItemAliasRepository extends JpaRepository<ItemAlias, Long> {

    /** 正規化済みの品名で 1 件。読取結果の自動紐付けはここを通る。 */
    Optional<ItemAlias> findByAliasText(String aliasText);

    /** 複数の品名をまとめて。確認画面は明細行が何行もあるので 1 往復で済ませる。 */
    @Query("select a from ItemAlias a join fetch a.ingredient where a.aliasText in :texts")
    List<ItemAlias> findAllByAliasTextIn(List<String> texts);

    /** ある食材に紐づく記憶。食材を消すときの巻き添えを確認するのに使う。 */
    List<ItemAlias> findByIngredientIdOrderByUpdatedAtDesc(Long ingredientId);

    /**
     * まだ内容量を教わっていない記憶。
     *
     * <p>画面で「あと ◯ 件教えれば在庫が完全になります」と出すため。
     * 宿題の残りが見えていると、人は片付けます。
     */
    @Query("select a from ItemAlias a join fetch a.ingredient where a.qtyPerUnit is null order by a.updatedAt desc")
    List<ItemAlias> findUnlearned();

    /** 全件（食材つき）。学習状況の一覧画面用。 */
    @Query("select a from ItemAlias a join fetch a.ingredient order by a.updatedAt desc")
    List<ItemAlias> findAllWithIngredient();
}
