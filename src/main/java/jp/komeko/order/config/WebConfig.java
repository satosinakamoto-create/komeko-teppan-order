package jp.komeko.order.config;

import jp.komeko.order.service.ImageStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web まわりの追加設定。
 *
 * <p>アップロードした商品画像は jar の中ではなくディスク上の {@code ./data/uploads}
 * に置かれるので、その場所を {@code /uploads/**} という URL で配信できるよう
 * リソースハンドラを登録します。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ImageStorageService imageStorageService;

    public WebConfig(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    /**
     * 「HTML を返すだけでロジックが無い」画面の登録場所。いまは空。
     *
     * <p><b>ログイン画面はここに書いていましたが、{@code LoginPageController} へ移しました。</b>
     *
     * <p>ビューコントローラは「URL とテンプレート名を結ぶだけ」の近道で、
     * 便利な代わりに<b>{@code @ControllerAdvice} で用意した共通のモデル属性が渡りません</b>
     * （{@code @Controller} のメソッドではないため、あの仕組みの対象外になる）。
     * そのため画面側で {@code ${guestLogin}} が常に空になり、
     * 「ゲストで参加する」のボタンが出ない、という詰まり方をしました。
     *
     * <p>共通の値を使う画面は、1 行の近道を使わず素直に {@code @Controller} で受けること。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 現在は登録なし
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = imageStorageService.getUploadDir().toUri().toString();
        registry.addResourceHandler(ImageStorageService.PUBLIC_PREFIX + "**")
                .addResourceLocations(location)
                // ブラウザに 1 時間キャッシュさせる（画像はほとんど変わらないため）
                .setCachePeriod(3600);
    }
}
