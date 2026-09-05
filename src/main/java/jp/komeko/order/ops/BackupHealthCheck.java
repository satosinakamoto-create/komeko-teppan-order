package jp.komeko.order.ops;

import jp.komeko.order.config.BackupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * バックアップが本当に取れているかを見張り、無事なら外部へ「生きています」を送る。
 *
 * <p><b>この仕組みが要る理由</b><br>
 * 2026-08-25 と 08-27〜08-29 の 4 日間、自動バックアップが 1 本も作られていませんでした。
 * 例外は握り潰され、失敗フラグは再起動で消え、ログはコンソールにしか出ず、
 * 結局<b>人がフォルダを ls して初めて分かりました</b>。
 * 1 人で運用する店では「見に行くのを忘れる」が常態なので、
 * 通知が無いということは、実質的に監視されていないのと同じです。
 *
 * <p><b>なぜ逆向きに作るのか（デッドマンスイッチ）</b><br>
 * 「異常を検知したら知らせる」仕組みは、異常のときに一緒に壊れます。
 * アプリが落ちていればアプリからの通知は飛びません。
 * そこで<b>無事なときだけ外部の URL を叩き、叩かれなくなったら外部が騒ぐ</b>形にします。
 * こうすると「バックアップが取れていない」も「アプリが死んだ」も「PC が落ちた」も
 * 「回線が切れた」も、すべて<b>同じ 1 つの症状（連絡が来ない）</b>として現れます。
 *
 * <p><b>報告ではなく結果を見る</b><br>
 * バックアップ処理が「成功した」と言ったかどうかは信用しません。
 * フォルダを実際に覗いて、新しい世代があるか、中身が空でないか、
 * 2 次コピー先にも届いているかを確かめます。
 */
@Service
public class BackupHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(BackupHealthCheck.class);

    /** BackupService が作るフォルダ名の形式（例: 20260830-181155）。 */
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** バックアップに必ず含まれるはずのファイル。 */
    private static final String DB_ZIP = "database.zip";

    private final BackupProperties backupProperties;
    private final OpsProperties opsProperties;
    private final Clock clock;
    private final RestClient restClient;

    /**
     * @param clock 時計。{@code LocalDateTime.now()} を直に呼ぶと
     *              「3 日前のバックアップしかない状態」をテストで作れなくなる。
     *              Bean は {@code InventoryConfig} が提供している（常に存在する）
     */
    public BackupHealthCheck(BackupProperties backupProperties,
                             OpsProperties opsProperties,
                             Clock clock) {
        this.backupProperties = backupProperties;
        this.opsProperties = opsProperties;
        this.clock = clock;
        this.restClient = RestClient.builder()
                .requestFactory(timeoutFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 生存信号が届かないより、待たされないことを優先する。
        // 失敗しても次の日また送るので、粘る意味がない。
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    // ========================================================================
    //  定時の点検
    // ========================================================================

    /**
     * 点検して、無事なら生存信号を送る。
     *
     * <p>既定では毎日 5:00（定時バックアップ 4:30 の 30 分後）。
     * 「取れたはずの時刻」を過ぎてから見ることで、取れなかった日を捕まえられます。
     */
    @Scheduled(cron = "${app.ops.heartbeat.cron:0 0 5 * * *}")
    public void checkAndReport() {
        BackupHealth health = inspect();

        if (!health.healthy()) {
            // ★ ここで生存信号を送らないことが、そのまま外部への通報になる。
            //   だから「送らない」の判断を握り潰さず、必ずログに残す。
            log.warn("バックアップの点検で問題が見つかりました。生存信号は送りません。{}", health.summary());
            return;
        }

        log.info("バックアップの点検: 正常（最新 {}）", health.latestGeneration());
        sendHeartbeat();
    }

    // ========================================================================
    //  点検の中身（HTTP を含まないので、そのままテストできる）
    // ========================================================================

    /**
     * バックアップの状態を実際のフォルダから確かめる。
     *
     * <p>ネットワークを触らないので、テストから直接呼べます。
     */
    public BackupHealth inspect() {
        List<String> problems = new ArrayList<>();

        if (!backupProperties.enabled()) {
            // 本番（PostgreSQL）では意図的に切ってある。切ってあるものを異常とは言わない。
            return BackupHealth.ok("（バックアップ機能は無効）", null);
        }

        Path dir = Paths.get(backupProperties.dir());
        if (!Files.isDirectory(dir)) {
            return BackupHealth.failed(
                    List.of("バックアップの保存先が見つかりません: " + dir.toAbsolutePath()), null, null);
        }

        Optional<Path> newest = newestGeneration(dir);
        if (newest.isEmpty()) {
            return BackupHealth.failed(List.of("バックアップが 1 つもありません"), null, null);
        }

        Path generation = newest.get();
        String name = generation.getFileName().toString();
        LocalDateTime takenAt = parseGenerationTime(name).orElse(null);

        // ── 1. 新しさ ──
        if (takenAt == null) {
            problems.add("最新世代の日時を読み取れません: " + name);
        } else {
            long hours = Duration.between(takenAt, LocalDateTime.now(clock)).toHours();
            int limit = opsProperties.heartbeat().backupMaxAgeHours();
            if (hours > limit) {
                problems.add("最後のバックアップから " + hours + " 時間経っています（上限 " + limit + " 時間）");
            }
        }

        // ── 2. 中身があるか ──
        //    フォルダだけ作られて中身が空、という壊れ方が実際にありうる。
        Path dbZip = generation.resolve(DB_ZIP);
        if (!Files.isRegularFile(dbZip)) {
            problems.add("最新世代に " + DB_ZIP + " がありません");
        } else if (sizeOf(dbZip) <= 0) {
            problems.add("最新世代の " + DB_ZIP + " が空です");
        }

        // ── 3. 2 次コピーが届いているか ──
        //    ここが本題。設定しただけで満足して、実は届いていない、を防ぐ。
        if (backupProperties.hasExtraCopy()) {
            Path extra = Paths.get(backupProperties.extraCopyDir()).resolve(name).resolve(DB_ZIP);
            if (!Files.isRegularFile(extra)) {
                problems.add("2 次コピー先に最新世代が届いていません: " + extra);
            }
        }

        return problems.isEmpty()
                ? BackupHealth.ok(name, takenAt)
                : BackupHealth.failed(problems, name, takenAt);
    }

    /** 生存信号を送る。設定が無ければ何もしない。 */
    private void sendHeartbeat() {
        OpsProperties.Heartbeat heartbeat = opsProperties.heartbeat();
        if (!heartbeat.isConfigured()) {
            log.debug("生存信号の宛先が未設定のため送信しません（app.ops.heartbeat.url）");
            return;
        }
        try {
            restClient.get().uri(heartbeat.url()).retrieve().toBodilessEntity();
            log.info("生存信号を送りました");
        } catch (Exception e) {
            // 送れなくても業務は続く。外部サービス側が「来なかった」として扱うだけ。
            log.warn("生存信号を送れませんでした: {}", e.toString());
        }
    }

    // ========================================================================
    //  補助
    // ========================================================================

    /** バックアップの保存先から、いちばん新しい世代のフォルダを探す。 */
    private Optional<Path> newestGeneration(Path dir) {
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> parseGenerationTime(p.getFileName().toString()).isPresent())
                    // フォルダ名が yyyyMMdd-HHmmss なので、文字列の並び順＝時刻の並び順になる
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            log.warn("バックアップの保存先を読めませんでした: {}", e.toString());
            return Optional.empty();
        }
    }

    /** フォルダ名から日時を読む。形式が違えば空を返す。 */
    private Optional<LocalDateTime> parseGenerationTime(String name) {
        try {
            return Optional.of(LocalDateTime.parse(name, DIR_FORMAT));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
