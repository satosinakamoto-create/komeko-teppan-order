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
     * ログイン画面のように「HTML を返すだけでロジックが無い」画面の登録。
     *
     * <p>Spring Security の {@code .loginPage("/login")} は
     * 「ログイン画面の URL はここです」と伝えるだけで、
     * <b>その URL を表示するコントローラまでは用意してくれません</b>。
     * 何も登録しないと GET /login が 404 になり、
     * さらに未ログイン時のリダイレクト先も /login なので無限ループに見える現象が起きます。
     *
     * <p>わざわざ空のコントローラクラスを作らなくても、こうして 1 行で登録できます。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("login");
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
