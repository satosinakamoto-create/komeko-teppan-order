package jp.komeko.order.repository;

import jp.komeko.order.domain.DiningTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 卓（テーブル）の DB アクセス。
 */
public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

    /** QR のトークンから卓を引く。お客さんの入口で使う。 */
    Optional<DiningTable> findByAccessToken(String accessToken);

    /** 管理画面用。使っていない卓も含めて並び順で。 */
    List<DiningTable> findAllByOrderBySortOrderAscIdAsc();

    /** 稼働中の卓だけ。 */
    List<DiningTable> findByActiveTrueOrderBySortOrderAscIdAsc();

    boolean existsByName(String name);
}
