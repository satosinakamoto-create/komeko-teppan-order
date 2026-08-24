package jp.komeko.order.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * ここまでのテストは {@link ProductionSafetyGuard#check} を直接呼ぶもので、
     * 守っているのは<b>判断の中身</b>（どの組み合わせを危険とみなすか）だけだった。
     *
     * <p>以下は<b>その判断が本当に起動時に実行されるか</b>＝ Bean としての配線を守る。
     * 分けて書いているのは、この 2 つが独立して壊れるから。
     * check() がどれだけ正しくても、Bean が作られなければ何も起きない。
     *
     * <p>実際 2026-08-25 に、{@code spring.main.lazy-initialization=true} を足すだけで
     * 装置が丸ごと無効化されること（＝ Bean が一度も生成されないこと）が実機で確認された。
     * その間、上の直接呼び出しテストはすべて green のままだった。
     * 同じ理由で「{@code @Component} が外れた」「{@code @Value} のプロパティ名がずれた」
     * 「コンストラクタから check() の呼び出しが消えた」も上のテストでは検出できない。
     *
     * <p>prod プロファイルは PostgreSQL への接続が前提なので {@code @SpringBootTest} では起動できない。
     * {@link ApplicationContextRunner} なら、この Bean 1 つだけの小さなコンテキストを組み立てて、
     * 実際の生成経路（コンストラクタ → {@code @Value} 解決 → check()）をそのまま通せる。
     */
    @Nested
    @DisplayName("Bean としての配線")
    class BeanWiring {

        /** guard 1 つだけを載せた、DB もサーバも起動しない最小のコンテキスト。 */
        private final ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(ProductionSafetyGuard.class);

        /**
         * 本番で lazy-initialization を有効にしたのと同じ状態を作る。
         *
         * <p>Spring Boot は {@code spring.main.lazy-initialization=true} を読むと
         * {@link LazyInitializationBeanFactoryPostProcessor} をコンテキストに足す、という実装なので、
         * ここでは同じものを直接足して再現している。
         * （{@link ApplicationContextRunner} は SpringApplication を通らないため、
         * プロパティを書くだけでは lazy にならない）
         */
        private ApplicationContextRunner lazyInitializationEnabled() {
            return runner.withInitializer(context ->
                    context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }

        @Test
        @DisplayName("prod でゲストログインが有効だと、コンテキストの起動そのものが失敗する")
        void guestLoginBreaksContextStartup() {
            // @Value のプロパティ名が app.guest-login からずれても、
            // コンストラクタから check() の呼び出しが消えても、ここが赤くなる
            runner.withPropertyValues("spring.profiles.active=prod", "app.guest-login=true")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("APP_GUEST_LOGIN");
                    });
        }

        @Test
        @DisplayName("prod と demo の同時指定でも、コンテキストの起動そのものが失敗する")
        void combinedProfilesBreakContextStartup() {
            runner.withPropertyValues("spring.profiles.active=prod,demo")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
                    });
        }

        @Test
        @DisplayName("lazy-initialization が有効でも、prod と demo の同時指定は起動できない")
        void guardStillFiresWhenLazyInitializationIsEnabled() {
            // ★ 2026-08-25 の実機事故そのもの。
            // この装置は「誰からも注入されない Bean」なので、lazy になった瞬間に
            // 一度も生成されず、例外を投げる機会を失う（＝黙って無効化される）。
            // 一方 DemoDataSeeder は ApplicationRunner なので lazy でも必ず走り、
            // 実店舗の DB に架空の伝票を書き、店舗設定を 24 時間受付に書き換えてしまう。
            lazyInitializationEnabled()
                    .withPropertyValues("spring.profiles.active=prod,demo")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("SPRING_PROFILES_ACTIVE");
                    });
        }

        @Test
        @DisplayName("lazy-initialization が有効でも、prod のゲストログインは起動できない")
        void guestLoginStillBlockedWhenLazyInitializationIsEnabled() {
            lazyInitializationEnabled()
                    .withPropertyValues("spring.profiles.active=prod", "app.guest-login=true")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("APP_GUEST_LOGIN");
                    });
        }

        @Test
        @DisplayName("prod 単独（ゲスト無効）なら、guard は Bean として載るが起動を邪魔しない")
        void plainProdStartsWithGuardBeanPresent() {
            runner.withPropertyValues("spring.profiles.active=prod")
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(ProductionSafetyGuard.class));
        }

        @Test
        @DisplayName("guard には @Component が付いている（Spring が Bean として拾う唯一の入口）")
        void guardIsAnnotatedAsComponent() {
            // 上の配線テストは Bean 定義を直接登録して確かめているので、
            // @Component が外れてもそれ自体には気づけない。
            // 実際の起動ではコンポーネントスキャンが唯一の登録経路なので、ここで別に押さえておく。
            // （config パッケージ全体をスキャンして確かめる手もあるが、
            //   SecurityConfig など DB や Security の依存を持つ Bean まで一緒に持ち込まれ、
            //   このテストが「重くて壊れやすいもの」に変わってしまう）
            assertThat(ProductionSafetyGuard.class.isAnnotationPresent(Component.class))
                    .as("ProductionSafetyGuard に @Component が付いていること")
                    .isTrue();
        }
    }
}
