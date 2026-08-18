package jp.komeko.order.web;

import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * いま表示している URL のパス（例: /admin/items/3/edit）。
     *
     * <p>サイドバーの「どの項目を光らせるか」の判定に使います。
     * コントローラごとに目印の文字列を渡す方式だと、画面を増やすたびに
     * 渡し忘れが起きるので、<b>URL という嘘をつけない情報源</b>から導出します。
     * Thymeleaf 3.1 でテンプレートから {@code #request} が参照できなくなったため、
     * ここでモデルに載せて渡しています。
     */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
