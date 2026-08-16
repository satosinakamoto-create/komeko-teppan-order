package jp.komeko.order.service;

import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.repository.ShopSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 店舗設定の読み書き。
 *
 * <p>設定は 1 行しかないので、無ければデフォルト値で作って返します。
 * 「必ず存在する」ことをこのサービスが保証するので、
 * 呼び出し側は {@code Optional} を気にせず使えます。
 */
@Service
public class ShopSettingService {

    private final ShopSettingRepository repository;

    /**
     * <b>コンストラクタインジェクション</b>。
     * Spring が {@code ShopSettingRepository} の実装を自動で渡してくれます。
     * フィールドに {@code @Autowired} を付ける書き方より、
     * final にできてテストしやすいのでこちらが推奨です。
     */
    public ShopSettingService(ShopSettingRepository repository) {
        this.repository = repository;
    }

    /** 設定を取得する（無ければ初期値で作成）。 */
    @Transactional
    public ShopSetting current() {
        return repository.findById(ShopSetting.SINGLETON_ID)
                .orElseGet(() -> repository.save(new ShopSetting()));
    }

    /** 画面表示だけの用途（書き込みしない）。 */
    @Transactional(readOnly = true)
    public ShopSetting currentReadOnly() {
        return repository.findById(ShopSetting.SINGLETON_ID).orElseGet(ShopSetting::new);
    }

    /** いまの営業日。 */
    @Transactional(readOnly = true)
    public LocalDate currentBusinessDate() {
        return currentReadOnly().businessDateOf(LocalDateTime.now());
    }

    /** 注文受付の一時停止／再開をトグルする。 */
    @Transactional
    public boolean toggleAccepting() {
        ShopSetting setting = current();
        setting.setAcceptingOrders(!setting.isAcceptingOrders());
        setting.touch();
        return setting.isAcceptingOrders();
    }

    /**
     * 管理画面からの保存。
     * 入力フォームの値を既存レコードに写して更新します
     * （新しいインスタンスを save すると id が固定 1 なので上書きになりますが、
     * 更新日時の管理などを一箇所にまとめるためこの形にしています）。
     */
    @Transactional
    public void save(ShopSetting form) {
        ShopSetting setting = current();
        setting.setShopName(form.getShopName());
        setting.setTagline(form.getTagline());
        setting.setAcceptingOrders(form.isAcceptingOrders());
        setting.setClosedMessage(form.getClosedMessage());
        setting.setOpenTime(form.getOpenTime());
        setting.setCloseTime(form.getCloseTime());
        setting.setLastOrderTime(form.getLastOrderTime());
        setting.setTaxRatePercent(form.getTaxRatePercent());
        setting.setOrderNumberStart(form.getOrderNumberStart());
        setting.setBusinessDayCutoverHour(form.getBusinessDayCutoverHour());
        setting.setGriddleCapacity(form.getGriddleCapacity());
        setting.setPickupNotice(form.getPickupNotice());
        setting.touch();
    }
}
