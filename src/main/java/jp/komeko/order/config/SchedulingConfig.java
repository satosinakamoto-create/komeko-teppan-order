package jp.komeko.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} を有効にするための設定。
 *
 * <p>Spring は「定期実行を使うぞ」と明示しないとスケジューラを起動しません。
 * このアプリでは {@code OrderEventPublisher#heartbeat} が
 * 25 秒ごとに SSE 接続の生存確認を送るために使っています。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
