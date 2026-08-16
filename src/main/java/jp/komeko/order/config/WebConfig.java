package jp.komeko.order.config;

import jp.komeko.order.service.ImageStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
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

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = imageStorageService.getUploadDir().toUri().toString();
        registry.addResourceHandler(ImageStorageService.PUBLIC_PREFIX + "**")
                .addResourceLocations(location)
                // ブラウザに 1 時間キャッシュさせる（画像はほとんど変わらないため）
                .setCachePeriod(3600);
    }
}
