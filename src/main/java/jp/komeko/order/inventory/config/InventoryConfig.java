package jp.komeko.order.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 在庫・仕入れモジュールの組み立て設定。
 *
 * <p><b>既存の設定クラスを書き換えないための置き場</b>です。
 * {@code KomekoOrderApplication} の {@code @EnableConfigurationProperties} に
 * 追記すれば済む話ですが、それは既存ファイルの変更になります。
 * 設定は自分の側に閉じておき、既存には触れない方針を通します。
 */
@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryConfig {

    /**
     * 時計。
     *
     * <p>{@code LocalDateTime.now()} を直に呼ぶと、日付にまつわる処理が
     * テストできなくなります（「受領から 10 日後に登録したら」を再現できない）。
     * 時計を Bean にしておくと、テストで固定した時刻に差し替えられます。
     *
     * <p>{@code @ConditionalOnMissingBean} を付けているのは、
     * 将来アプリ全体で時計を管理するようになったとき、
     * このモジュールの都合で二重定義にならないようにするためです。
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
