package jp.komeko.order;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1（土台のスキーマ）を書き起こすための<b>使い捨ての道具</b>。ふだんは走りません。
 *
 * <p>アプリを<b>本物の PostgreSQL につないで起動し</b>、そのとき Hibernate が
 * 「このエンティティ群を入れるならこういうテーブルが要る」と考えた CREATE 文を
 * {@code target/baseline-ddl.sql} に書き出させます。
 * 21 テーブルぶんの CREATE を手で書くと、必ずどこかで型か NOT NULL を間違えます。
 *
 * <p>走らせ方:
 * <pre>
 *   .\.tools\apache-maven-3.9.9\bin\mvn.cmd test -Dtest=BaselineDdlDumpTest -D"dump.ddl=true"
 * </pre>
 *
 * <p><b>アプリごと起動しているのが要点です。</b>
 * Hibernate を単体で組み立てて出力すると、命名規約（Spring Boot は
 * {@code tableChargeAmount} を {@code table_charge_amount} にする）や方言の解決が
 * 本番とずれます。ずれた V1 を作ると、<b>本番の起動時に validate が全部の列で落ちます。</b>
 * 同じ Spring の設定を通しておけば、そのずれが原理的に起きません。
 *
 * <p><b>出てきた SQL をそのまま V1 にはしません。</b>
 * 人が読んで並びを整え、コメントを足してから {@code V1__baseline.sql} にします。
 * そして正しさを保証するのはこの道具ではなく、
 * {@code FreshDatabaseBootTest}（Flyway を流してから validate させる）のほうです。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "dump.ddl", matches = "true")
class BaselineDdlDumpTest {

    private static final Path OUTPUT = Path.of("target/baseline-ddl.sql");

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
        // JPA 標準のスクリプト生成。DB は触らず、SQL をファイルに書くだけ。
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action",
                () -> "create");
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                () -> OUTPUT.toString());
        // 仕込みデータは要らない（テーブルが無いので走らせると落ちる）
        registry.add("app.demo-data", () -> "false");
    }

    @Test
    void dump() throws Exception {
        assertThat(Files.exists(OUTPUT))
                .as("DDL が書き出されていない: %s", OUTPUT.toAbsolutePath())
                .isTrue();

        String ddl = Files.readString(OUTPUT);
        System.out.println("=== " + OUTPUT.toAbsolutePath() + " ===");
        System.out.println(ddl);

        // 命名規約が本番と同じかを、ここで一目で確かめておく。
        // キャメルケースのまま出ていたら、その V1 は本番で使えない。
        assertThat(ddl).contains("table_charge_amount");
    }
}
