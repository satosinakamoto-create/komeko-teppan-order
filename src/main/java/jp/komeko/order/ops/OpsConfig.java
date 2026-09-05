package jp.komeko.order.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 運用の見張りの組み立て設定。
 *
 * <p>既存の {@code KomekoOrderApplication} の
 * {@code @EnableConfigurationProperties} に追記すれば済みますが、
 * それは既存ファイルの変更になります。設定は自分の側に閉じておきます。
 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class OpsConfig {
}
