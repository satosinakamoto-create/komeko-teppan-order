package jp.komeko.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * タイムゾーンの固定が効いていることを守るテスト。
 *
 * <p>深夜料金・営業日・ラストオーダーはすべて JVM の既定タイムゾーン越しの
 * 壁時計で判定される。既定が UTC のまま動くと、例外を出さずに
 * 「昼の 13 時に深夜料金が乗る」形で現れる（2026-08-22 に本番で実際に起きた）。
 * Dockerfile の TZ 指定はホスティング側の都合で効かないことがあるため、
 * {@link KomekoOrderApplication} がコードで固定している。ここではその固定が
 * 「UTC で起動してしまった JVM」でも効くことを直接確かめる。
 */
@DisplayName("タイムゾーンの固定")
class TimeZoneFixTest {

    private TimeZone original;

    @BeforeEach
    void rememberDefault() {
        original = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefault() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("UTC で起動した JVM でも Asia/Tokyo に固定される")
    void fixesDefaultZoneEvenIfJvmStartedInUtc() {
        // Render で実際に起きた状態（TZ 指定が効かず UTC で起動）を作る
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        KomekoOrderApplication.applyFixedTimeZone();

        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
        assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    @DisplayName("クラスを読み込んだ時点で既定タイムゾーンは Asia/Tokyo になっている")
    void staticInitializerHasAlreadyFixedTheZone() {
        // KomekoOrderApplication は @SpringBootTest 系のテストで必ず読み込まれるため、
        // このテストが走る時点で static イニシャライザは実行済みのはず。
        // （マシンの既定がたまたま JST でも、上の UTC ケースが本体を検証している）
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
    }
}
