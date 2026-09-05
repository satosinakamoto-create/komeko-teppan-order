package jp.komeko.order.ops;

import jp.komeko.order.config.BackupProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BackupHealthCheck} のテスト。
 *
 * <p><b>このテストが守っているもの＝「気づけること」そのもの</b><br>
 * 2026-08-25 と 08-27〜08-29 の 4 日間、バックアップが 1 本も走っていないのに
 * 誰も気づきませんでした。この点検が正しく「異常」と言えなければ、
 * 生存信号は送られ続け、外部サービスは「正常」と表示し続けます。
 * <b>壊れた監視は、監視が無いより悪い</b>（安心だけ与えるので）。
 * だから「どういうときに異常と言うか」を数字で固定します。
 *
 * <p><b>時計を固定する理由</b><br>
 * 「最後のバックアップから何時間経ったか」を見る処理なので、
 * 現在時刻を外から差し込めないとテストが書けません。
 * 実際の時刻に依存させると、深夜に流したときだけ落ちるテストになります。
 */
@DisplayName("バックアップの見張り（デッドマンスイッチ）")
class BackupHealthCheckTest {

    /** テスト中の「いま」。2026-08-30 の朝 5 時（点検が走る時刻）。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 30, 5, 0);

    @TempDir
    Path backupDir;

    @TempDir
    Path extraDir;

    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    // ── 組み立ての助け ──

    private BackupHealthCheck check(boolean withExtraCopy) {
        BackupProperties backup = new BackupProperties(
                true, backupDir.toString(), "0 30 4 * * *", 14,
                true, true, 20, 10,
                withExtraCopy ? extraDir.toString() : "");
        OpsProperties ops = new OpsProperties(
                new OpsProperties.Heartbeat(null, 26, "0 0 5 * * *"));
        return new BackupHealthCheck(backup, ops, clock);
    }

    /** 世代フォルダを作る。{@code emptyZip} なら中身 0 バイトの zip を置く。 */
    private void createGeneration(Path parent, String name, boolean withDbZip, boolean emptyZip)
            throws IOException {
        Path dir = Files.createDirectories(parent.resolve(name));
        if (withDbZip) {
            Path zip = dir.resolve("database.zip");
            Files.write(zip, emptyZip ? new byte[0] : "dummy".getBytes());
        }
    }

    @Nested
    @DisplayName("正常と判定する場合")
    class Healthy {

        @Test
        @DisplayName("今朝のバックアップがあれば正常")
        void fresh_backup_is_healthy() throws IOException {
            createGeneration(backupDir, "20260830-043000", true, false);

            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isTrue();
            assertThat(health.latestGeneration()).isEqualTo("20260830-043000");
        }

        @Test
        @DisplayName("2 次コピー先にも届いていれば正常")
        void extra_copy_present_is_healthy() throws IOException {
            createGeneration(backupDir, "20260830-043000", true, false);
            createGeneration(extraDir, "20260830-043000", true, false);

            assertThat(check(true).inspect().healthy()).isTrue();
        }

        @Test
        @DisplayName("バックアップ機能を意図的に切ってあるなら異常ではない")
        void disabled_is_not_a_problem() {
            // 本番（PostgreSQL）では BackupService が動かないので明示的に切ってある。
            // 切ってあるものを「異常」と言い続けると、オオカミ少年になる。
            BackupProperties disabled = new BackupProperties(
                    false, backupDir.toString(), "0 30 4 * * *", 14,
                    true, true, 20, 10, "");
            OpsProperties ops = new OpsProperties(new OpsProperties.Heartbeat(null, 26, null));

            assertThat(new BackupHealthCheck(disabled, ops, clock).inspect().healthy()).isTrue();
        }

        @Test
        @DisplayName("古い世代が混ざっていても、最新が新しければ正常")
        void picks_the_newest_generation() throws IOException {
            createGeneration(backupDir, "20260820-043000", true, false);   // 10日前
            createGeneration(backupDir, "20260830-043000", true, false);   // 今朝
            createGeneration(backupDir, "20260824-043000", true, false);   // 6日前

            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isTrue();
            assertThat(health.latestGeneration()).isEqualTo("20260830-043000");
        }
    }

    @Nested
    @DisplayName("異常と判定する場合（生存信号を送らない＝外部が騒ぐ）")
    class Unhealthy {

        @Test
        @DisplayName("4 日間バックアップが無ければ異常（実際に起きた事故）")
        void stale_backup_is_detected() throws IOException {
            // 2026-08-26 を最後に、27・28・29 と 1 本も取れていない状態。
            // これが「誰にも気づかれなかった」実際の状況。
            createGeneration(backupDir, "20260826-180519", true, false);

            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isFalse();
            assertThat(health.problems()).anyMatch(p -> p.contains("時間経っています"));
        }

        @Test
        @DisplayName("26 時間が境目（27 時間前は異常、25 時間前は正常）")
        void age_boundary() throws IOException {
            // 毎日 4:30 に取るので、丸一日以上空いたら異常。
            // 境目を固定しておかないと、判定がいつの間にかズレる。
            createGeneration(backupDir, "20260829-020000", true, false);   // 27時間前
            assertThat(check(false).inspect().healthy()).isFalse();

            Path other = backupDir.resolve("20260829-020000");
            Files.delete(other.resolve("database.zip"));
            Files.delete(other);

            createGeneration(backupDir, "20260829-040000", true, false);   // 25時間前
            assertThat(check(false).inspect().healthy()).isTrue();
        }

        @Test
        @DisplayName("バックアップが 1 つも無ければ異常")
        void no_backup_at_all() {
            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isFalse();
            assertThat(health.problems()).anyMatch(p -> p.contains("1 つもありません"));
        }

        @Test
        @DisplayName("フォルダだけあって中身が無ければ異常")
        void empty_generation_is_detected() throws IOException {
            // 「フォルダは作られたが zip が無い」という壊れ方がありうる。
            // フォルダの存在だけを見ていると、これを見逃す。
            createGeneration(backupDir, "20260830-043000", false, false);

            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isFalse();
            assertThat(health.problems()).anyMatch(p -> p.contains("database.zip がありません"));
        }

        @Test
        @DisplayName("zip が 0 バイトなら異常")
        void empty_zip_is_detected() throws IOException {
            createGeneration(backupDir, "20260830-043000", true, true);

            BackupHealth health = check(false).inspect();

            assertThat(health.healthy()).isFalse();
            assertThat(health.problems()).anyMatch(p -> p.contains("空です"));
        }

        @Test
        @DisplayName("2 次コピー先に届いていなければ異常（設定しただけで満足しない）")
        void missing_extra_copy_is_detected() throws IOException {
            // extra-copy-dir を設定したのに実際は届いていない、が一番怖い。
            // 画面には「バックアップ成功」と出るので、二重化された気になってしまう。
            createGeneration(backupDir, "20260830-043000", true, false);
            // extraDir には作らない

            BackupHealth health = check(true).inspect();

            assertThat(health.healthy()).isFalse();
            assertThat(health.problems()).anyMatch(p -> p.contains("2 次コピー先"));
        }

        @Test
        @DisplayName("保存先フォルダそのものが無ければ異常")
        void missing_backup_dir() {
            BackupProperties broken = new BackupProperties(
                    true, backupDir.resolve("does-not-exist").toString(), "0 30 4 * * *", 14,
                    true, true, 20, 10, "");
            OpsProperties ops = new OpsProperties(new OpsProperties.Heartbeat(null, 26, null));

            assertThat(new BackupHealthCheck(broken, ops, clock).inspect().healthy()).isFalse();
        }
    }

    @Nested
    @DisplayName("設定の既定値")
    class Defaults {

        @Test
        @DisplayName("URL が空なら「送らない」と判断できる")
        void heartbeat_not_configured() {
            assertThat(new OpsProperties.Heartbeat("", 0, null).isConfigured()).isFalse();
            assertThat(new OpsProperties.Heartbeat(null, 0, null).isConfigured()).isFalse();
            assertThat(new OpsProperties.Heartbeat("https://hc-ping.com/x", 0, null).isConfigured()).isTrue();
        }

        @Test
        @DisplayName("書かなかった項目には意味のある既定が入る")
        void fills_defaults() {
            OpsProperties.Heartbeat h = new OpsProperties.Heartbeat(null, 0, null);

            assertThat(h.backupMaxAgeHours()).isEqualTo(26);
            assertThat(h.cron()).isEqualTo("0 0 5 * * *");
        }
    }
}
