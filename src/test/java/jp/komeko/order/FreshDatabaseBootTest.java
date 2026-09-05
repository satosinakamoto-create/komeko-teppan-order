package jp.komeko.order;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>まっさらな PostgreSQL でアプリが起動できるか</b>を確かめるテスト。
 *
 * <p><b>これが無かったせいで、2026-09-05 まで起動できませんでした。</b><br>
 * Flyway を入れたのは在庫機能（V2）からで、それより前の土台 13 テーブルは
 * Hibernate が開発中に作ったものを「もうある前提」で扱っていました。
 * 開発機と公開デモには最初からテーブルがあるので、誰も気づきません。
 * 気づくのは<b>店のサーバーに新しい DB を立てた日</b>です。
 * いちばん高くつく気づき方なので、ここで毎回確かめます。
 *
 * <p><b>{@code PostgresMigrationTest} との違い</b><br>
 * あちらは「書いた SQL が PostgreSQL の方言で通るか」を見ます。
 * こちらは「流し終えた DB に、アプリが求める列が<b>全部そろっているか</b>」を見ます。
 * エンティティに列を足してマイグレーションを書き忘れると、
 * SQL 自体は通るのであちらは緑のまま、こちらだけが落ちます。
 *
 * <p><b>マイグレーションを数え上げないこと。</b>
 * ファイル名を並べて書くと、V10 を足した人がここを直し忘れます
 * （実際 {@code PostgresMigrationTest} は V5 で止まったまま、
 * V6〜V9 が一度も PostgreSQL で流されていませんでした）。
 * ここは Flyway 自身に {@code db/migration} を読ませています。
 *
 * <p>Docker は要りません。PostgreSQL の実体をテストの中で起動します。
 */
@DisplayName("まっさらな PostgreSQL で起動できる")
class FreshDatabaseBootTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** 空のスキーマを作り、本番と同じ設定で Flyway を流す。 */
    private Flyway migrateInto(String schema) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                // application-prod.yml と同じ。空の DB では効かない（＝V1 から流れる）
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();
        return flyway;
    }

    private List<String> columnsOf(String schema, String table) throws Exception {
        List<String> columns = new ArrayList<>();
        String sql = """
                select column_name from information_schema.columns
                where table_schema = ? and table_name = ?
                """;
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    @Test
    @DisplayName("★ V1 から最後まで、順番どおりに流れる")
    void allMigrationsRunFromScratch() throws Exception {
        Flyway flyway = migrateInto("fresh_all");

        // 実際に SQL として流れたものだけを数える（ベースラインの目印は除く）
        List<String> executedAsSql = Arrays.stream(flyway.info().applied())
                .filter(i -> i.getVersion() != null)
                .filter(i -> "SQL".equals(i.getType().name()))
                .map(i -> i.getVersion().toString())
                .toList();

        // V1 が実際に流れていること。ここが空だと、土台が無い DB ができる
        assertThat(executedAsSql).as("V1（土台）が流れていない").contains("1");
        // db/migration にあるファイルを全部流したこと。
        // ファイル名を数え上げないので、V10 を足しても直しに来る必要がない
        int files = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql").length;
        assertThat(executedAsSql)
                .as("db/migration に %d 本あるのに %d 本しか流れていない", files, executedAsSql.size())
                .hasSize(files);
    }

    @Test
    @DisplayName("★ 流し終えた DB を Hibernate が validate できる（＝本番が起動する）")
    void hibernateValidatesTheResultingSchema() throws Exception {
        migrateInto("fresh_validate");

        // 本番と同じ ddl-auto: validate。列が 1 つでも足りなければ例外で落ちる。
        // 落ちなければ「本番プロファイルで起動できる」ということ。
        var props = new java.util.Properties();
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("hibernate.hbm2ddl.auto", "validate");
        props.put("hibernate.default_schema", "fresh_validate");
        props.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        props.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        props.put("hibernate.connection.datasource", dataSource);

        var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                .applySettings(props)
                .build();
        try {
            var sources = new org.hibernate.boot.MetadataSources(registry);
            var scanner = new org.springframework.context.annotation
                    .ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new org.springframework.core.type.filter
                    .AnnotationTypeFilter(jakarta.persistence.Entity.class));
            for (var bean : scanner.findCandidateComponents("jp.komeko.order")) {
                sources.addAnnotatedClassName(bean.getBeanClassName());
            }
            // SessionFactory を組み立てる瞬間に validate が走る。
            // ここで落ちたら、エンティティに足した列のマイグレーションが無い。
            sources.buildMetadata().buildSessionFactory().close();
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @DisplayName("あとから足した列が、ちゃんと最後に付いている")
    void laterMigrationsAddTheirColumns() throws Exception {
        migrateInto("fresh_columns");

        // V6 / V8 / V9 が足した列。V1 に混ぜてしまうと
        // 「もう入っている DB」に二重で足そうとして落ちるので、順番ごと確かめる
        assertThat(columnsOf("fresh_columns", "shop_setting")).contains("monthly_rent");
        assertThat(columnsOf("fresh_columns", "option_group")).contains("allow_duplicate");
        assertThat(columnsOf("fresh_columns", "order_line_option")).contains("quantity");
        assertThat(columnsOf("fresh_columns", "table_session"))
                .contains("payment_method", "charge_exempt_count");
        // V7 が作ったテーブル
        assertThat(columnsOf("fresh_columns", "service_call")).isNotEmpty();
    }

    @Test
    @DisplayName("すでにテーブルがある DB では V1 を流さない（baseline-on-migrate）")
    void existingDatabaseIsBaselinedInsteadOfRerunningV1() throws Exception {
        // 本番はまだ立っていないが、いま動いている開発機や公開デモは
        // 「テーブルはあるが Flyway の履歴が無い」状態にあたる。
        // そこへ V1 を流すと "table already exists" で起動しなくなる。
        //
        // 「土台がすでにある DB」は、V1 を Flyway を通さずに流して作る。
        // 一部のテーブルだけを手で書いた代役にすると、V4 が menu_item を
        // 見つけられずに落ちる（＝この検証とは関係のない理由で赤くなる）。
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS fresh_existing CASCADE");
            s.execute("CREATE SCHEMA fresh_existing");
            s.execute("SET search_path TO fresh_existing");
            s.execute(new String(new org.springframework.core.io.ClassPathResource(
                    "db/migration/V1__baseline.sql").getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas("fresh_existing")
                .defaultSchema("fresh_existing")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();

        // ★ バージョン番号だけを見てはいけない。
        //   Flyway は「ここまで済んだことにする」という目印そのものを
        //   バージョン 1 として履歴に残すので、applied には必ず "1" が現れる。
        //   見分けるのは種別のほう。SQL として実行されたかどうかを見る。
        List<String> executedAsSql = Arrays.stream(flyway.info().applied())
                .filter(i -> i.getVersion() != null)
                .filter(i -> "SQL".equals(i.getType().name()))
                .map(i -> i.getVersion().toString())
                .toList();

        // V1 は流れず（テーブルはもうあるので流れたら "already exists" で落ちる）、
        // V2 から続いていること
        assertThat(executedAsSql).as("V1 が流れてしまっている").doesNotContain("1");
        assertThat(executedAsSql).contains("2", "9");
    }
}
