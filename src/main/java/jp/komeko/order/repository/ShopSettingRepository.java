package jp.komeko.order.repository;

import jp.komeko.order.domain.ShopSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettingRepository extends JpaRepository<ShopSetting, Long> {
}
