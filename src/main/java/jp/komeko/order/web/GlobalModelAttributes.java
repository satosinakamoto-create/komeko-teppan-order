package jp.komeko.order.web;

import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * すべての画面で使う値をモデルに詰めるクラス。
 *
 * <p><b>{@code @ControllerAdvice} とは</b><br>
 * 「全コントローラに共通で効く設定」を書く場所です。
 * ここで {@code @ModelAttribute} を定義しておくと、
 * どのコントローラから画面を返しても自動でモデルに入るので、
 * 全コントローラで {@code model.addAttribute("shop", ...)} を書く必要がなくなります。
 *
 * <p>店舗名やタグラインはヘッダー・タイトルで必ず使うので、ここに置いています。
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private final ShopSettingService shopSettingService;

    public GlobalModelAttributes(ShopSettingService shopSettingService) {
        this.shopSettingService = shopSettingService;
    }

    @ModelAttribute("shop")
    public ShopSetting shop() {
        return shopSettingService.currentReadOnly();
    }
}
