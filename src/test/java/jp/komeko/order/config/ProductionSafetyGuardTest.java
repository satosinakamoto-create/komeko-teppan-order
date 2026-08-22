package jp.komeko.order.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 実店舗（prod）をデモ用の設定ミスから守る安全装置のテスト。
 *
 * <p>「環境変数 1 つで事故になる」組み合わせは、起動そのものを失敗させる。
 * ここが緩むと、実店舗で誰でもスタッフ画面に入れたり、
 * 実データベースに架空の伝票が混ざったりする。
 */
@DisplayName("prod の安全装置")
class ProductionSafetyGuardTest {

    @Test
    @DisplayName("prod でゲストログインが有効なら、起動できない")
    void prodWithGuestLoginFailsFast() {
        assertThatThrownBy(() -> ProductionSafetyGuard.check(List.of("prod"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_GUEST_LOGIN");
    }

    @Test
    @DisplayName("prod と demo の同時指定でも、起動できない")
    void prodCombinedWithDemoFailsFast() {
        assertThatThrownBy(() -> ProductionSafetyGuard.check(List.of("demo", "prod"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @Test
    @DisplayName("prod と dev の同時指定でも、起動できない")
    void prodCombinedWithDevFailsFast() {
        assertThatThrownBy(() -> ProductionSafetyGuard.check(List.of("prod", "dev"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
    }

    @Test
    @DisplayName("prod 単独（ゲスト無効）なら、普通に起動できる")
    void plainProdStartsNormally() {
        assertThatCode(() -> ProductionSafetyGuard.check(List.of("prod"), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("デモ環境（demo + ゲスト有効）は、この装置に触れない")
    void demoEnvironmentIsUnaffected() {
        assertThatCode(() -> ProductionSafetyGuard.check(List.of("demo"), true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev 単独（ふだんの開発）も、この装置に触れない")
    void devEnvironmentIsUnaffected() {
        assertThatCode(() -> ProductionSafetyGuard.check(List.of("dev"), false))
                .doesNotThrowAnyException();
    }
}
