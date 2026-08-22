package jp.komeko.order;

import jp.komeko.order.config.AppProperties;
import jp.komeko.order.config.BackupProperties;
import jp.komeko.order.config.SecurityAccessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

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

    /*
     * 業務時刻の基準を Asia/Tokyo に「コードで」固定する。
     *
     * このアプリは営業日の切り替え（5:00）・ラストオーダー・深夜料金を
     * すべて日本時間の壁時計（LocalDateTime.now()）で判断している。
     * JVM の既定タイムゾーンがずれると、例外は一切出ないまま
     * 「昼の 13 時に深夜料金 10% が乗る」「売上の営業日が 1 日ずれる」
     * という形でだけ現れる。
     *
     * Dockerfile では ENV TZ=Asia/Tokyo と -Duser.timezone を二重に
     * 指定していたが、2026-08-22 に本番（Render）で実測したところ
     * どちらも効いておらず、JST 13 時の伝票に深夜料金が乗っていた
     * （UTC 23:00〜05:00 = JST 8:00〜14:00 なので、日本の日中に毎日出る）。
     * ホスティング側の設定は画面から消えたり上書きされたりし得るので、
     * 環境変数には頼らず、ここで固定する。
     *
     * static イニシャライザに置いているのは、main() 経由でも
     * テスト（@SpringBootTest はこのクラスを読み込む）経由でも、
     * Spring が動き出す前に必ず 1 回実行されるから。
     */
    static {
        applyFixedTimeZone();
    }

    /** JVM の既定タイムゾーンを Asia/Tokyo にそろえる（理由は上のコメント）。 */
    static void applyFixedTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    public static void main(String[] args) {
        SpringApplication.run(KomekoOrderApplication.class, args);
    }
}
