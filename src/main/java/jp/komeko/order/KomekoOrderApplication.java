package jp.komeko.order;

import jp.komeko.order.config.AppProperties;
import jp.komeko.order.config.BackupProperties;
import jp.komeko.order.config.SecurityAccessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * アプリケーションの入口。
 *
 * <p>Java のプログラムは必ず {@code main} メソッドから始まります。
 * {@link SpringApplication#run} を呼ぶと Spring Boot が
 *
 * <ol>
 *   <li>同じパッケージ配下のクラスを走査して {@code @Component} / {@code @Service} /
 *       {@code @Controller} などが付いたクラスを見つけ</li>
 *   <li>それらのインスタンス（Bean）を生成して互いに結びつけ（DI）</li>
 *   <li>組み込み Tomcat を 8080 番ポートで起動する</li>
 * </ol>
 *
 * ということを自動でやってくれます。
 *
 * <p>起動したら以下の URL にアクセスできます。
 * <ul>
 *   <li>http://localhost:8080/           … お客さん用メニュー（QR の飛び先）</li>
 *   <li>http://localhost:8080/kitchen    … 厨房用オーダー画面（要ログイン）</li>
 *   <li>http://localhost:8080/display    … 番号呼び出しサイネージ</li>
 *   <li>http://localhost:8080/admin      … 管理画面（要 ADMIN 権限）</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, BackupProperties.class, SecurityAccessProperties.class})
public class KomekoOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(KomekoOrderApplication.class, args);
    }
}
