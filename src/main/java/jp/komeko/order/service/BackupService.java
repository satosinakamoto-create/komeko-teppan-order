package jp.komeko.order.service;

import jakarta.annotation.PreDestroy;
import jp.komeko.order.config.BackupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 自動バックアップ。
 *
 * <p><b>何を守るためのクラスか</b><br>
 * このアプリのデータ（注文・伝票・会計）は H2 データベースのファイル 1 個に入っています。
 * トランザクションのおかげで停電しても壊れませんが、
 * <b>ディスクそのものが故障したら全部消えます</b>。
 * そこで「別の場所にコピーを取り続ける」のがこのクラスの仕事です。
 *
 * <p><b>バックアップが走る 3 つのタイミング</b>
 * <ol>
 *   <li><b>定時</b>（既定 毎日 4:30）… 閉店（翌2:00）の後、営業日切替（5:00）の前。
 *       1 営業日分がそろった状態で取れる</li>
 *   <li><b>アプリ終了時</b> … 閉店後に PC ごと落とす店では 4:30 に PC が起きていない。
 *       その場合はこちらが本命になる（正常終了時のみ。電源断では走らない）</li>
 *   <li><b>手動</b> … 管理画面のボタン。大事な変更をした直後などに</li>
 * </ol>
 *
 * <p><b>H2 の {@code BACKUP TO} を使う理由</b><br>
 * 動作中の DB ファイルをただコピーすると、書き込み途中の壊れたコピーになる恐れがあります。
 * {@code BACKUP TO} は H2 自身が整合性を保証しながら zip を作る SQL コマンドで、
 * <b>営業中でも安全に</b>実行できます。
 *
 * <p><b>復元をあえて自動化していない理由</b><br>
 * リストアは「いまのデータを過去の状態で上書きする」操作で、
 * ボタン一つで実行できると誤操作の被害が最大級になります。
 * 復元は docs/バックアップと復元.md の手順に従って人間が行います。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** バックアップフォルダの名前（例: 20260817-043000）。 */
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * バックアップフォルダと認識する名前のパターン。
     * 自動削除（世代管理）の対象をこれに一致するものだけに絞ることで、
     * 保存先に他のファイルが置かれていても誤って消さないようにする。
     */
    private static final Pattern DIR_PATTERN = Pattern.compile("\\d{8}-\\d{6}(-\\d+)?");

    private final JdbcTemplate jdbcTemplate;
    private final BackupProperties properties;
    private final ImageStorageService imageStorageService;

    // ── 直近の実行状況（管理画面に出す）。
    //    スケジューラと HTTP の両方から触られるので synchronized で守る ──
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastAttemptAt;
    private String lastMessage = "まだ実行されていません";
    private boolean lastFailed = false;

    public BackupService(JdbcTemplate jdbcTemplate,
                         BackupProperties properties,
                         ImageStorageService imageStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.imageStorageService = imageStorageService;
    }

    // ========================================================================
    //  実行のきっかけ
    // ========================================================================

    /** ① 定時実行。cron は application.yml で変更できる。 */
    @Scheduled(cron = "${app.backup.cron:0 30 4 * * *}")
    public void scheduledBackup() {
        if (!properties.enabled()) {
            return;
        }
        runQuietly("定時");
    }

    /**
     * ①' 起動時の「追いつきバックアップ」。
     *
     * <p><b>なぜ要るのか</b><br>
     * 定時実行（{@link #scheduledBackup}）はアプリが動いている間しか走りません。
     * 閉店後に PC を落とす店では 4:30 に PC が存在しないので、定時は一度も走らない。
     * しかも Spring のスケジューラには「逃した回をあとで実行する」機能がありません。
     *
     * <p>そこで、起動のたびに「最後のバックアップからどれくらい空いたか」を確かめ、
     * しきい値（既定 20 時間）以上空いていたら 1 本取ります。
     * これで前夜に電源を直接切られても、翌日アプリを起動した瞬間に追いつけます。
     * 昨夜の分はトランザクションのおかげで DB に無事残っているので、
     * ここで取るバックアップにはそれも全部含まれます。
     *
     * <p>{@link ApplicationReadyEvent} は「アプリの起動が完全に終わった」合図です。
     * 起動処理の途中でバックアップを走らせて起動を遅くしないよう、このタイミングにしています。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backupOnStartupIfStale() {
        if (!properties.enabled() || !properties.backupOnStartup()) {
            return;
        }
        LocalDateTime newest = newestBackupTime();
        if (newest != null && Duration.between(newest, LocalDateTime.now()).toHours()
                < properties.startupMaxAgeHours()) {
            log.info("最後のバックアップ（{}）から {} 時間経っていないため、起動時バックアップを見送ります",
                    newest, properties.startupMaxAgeHours());
            return;
        }
        runQuietly("起動時");
    }

    /**
     * ② アプリ終了時。
     *
     * <p>{@code @PreDestroy} は Spring がアプリを畳むときに呼ぶフックです。
     * Ctrl+C や PC のシャットダウン（正常な終了）では走りますが、
     * <b>電源を直接引き抜いた場合は走りません</b>。だから定時と手動が保険として要ります。
     */
    @PreDestroy
    public void backupOnShutdown() {
        if (!properties.enabled() || !properties.backupOnShutdown()) {
            return;
        }
        // 開発中は保存のたびに DevTools がアプリを再起動する＝そのたび @PreDestroy が走る。
        // 毎回取っていたらバックアップだらけになるので、直近に成功していれば見送る。
        if (withinMinInterval()) {
            log.info("直近 {} 分以内にバックアップ済みのため、終了時バックアップを見送ります",
                    properties.minIntervalMinutes());
            return;
        }
        runQuietly("終了時");
    }

    /** 自動実行用：失敗してもアプリ本体を巻き込まない（記録だけして続行）。 */
    private void runQuietly(String trigger) {
        try {
            backupNow(trigger);
        } catch (Exception e) {
            // 状態の記録とログ出力は backupNow の中で済んでいる
        }
    }

    // ========================================================================
    //  本体
    // ========================================================================

    /**
     * ③ 手動実行にも使う本体。いますぐバックアップを取る。
     *
     * @param trigger 記録用のきっかけ名（定時／終了時／手動）
     * @return 画面に出す結果メッセージ
     * @throws IllegalStateException 失敗したとき（メッセージはそのまま画面に出せる）
     */
    public synchronized String backupNow(String trigger) {
        lastAttemptAt = LocalDateTime.now();
        try {
            if (!isH2()) {
                throw new IllegalStateException(
                        "この DB（" + databaseProductName() + "）はアプリ内バックアップの対象外です。"
                                + "PostgreSQL の場合は pg_dump を使ってください");
            }

            Path root = Paths.get(properties.dir()).toAbsolutePath().normalize();
            Files.createDirectories(root);

            Path target = uniqueDir(root, LocalDateTime.now().format(DIR_FORMAT));
            Files.createDirectories(target);

            // ── ① DB 本体。H2 に「整合性の取れた zip を作れ」と頼む ──
            String dbZipPath = target.resolve("database.zip").toString();
            jdbcTemplate.execute("BACKUP TO '" + dbZipPath.replace("'", "''") + "'");

            // ── ② 商品画像 ──
            int imageCount = zipDirectory(imageStorageService.getUploadDir(), target.resolve("uploads.zip"));

            // ── ③ 古い世代の間引き ──
            int pruned = prune(root);

            // ── ④ 2次コピー（設定されていれば） ──
            String extraNote = copyToExtraDir(target);

            long totalBytes = directorySize(target);
            String message = "%s バックアップ成功: %s（%s、画像 %d 枚%s%s）".formatted(
                    trigger, target.getFileName(), formatBytes(totalBytes), imageCount,
                    pruned > 0 ? "、古い %d 世代を削除".formatted(pruned) : "",
                    extraNote);

            lastSuccessAt = LocalDateTime.now();
            lastMessage = message;
            lastFailed = false;
            log.info(message);
            return message;

        } catch (Exception e) {
            String message = "%s バックアップ失敗: %s".formatted(trigger,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            lastMessage = message;
            lastFailed = true;
            log.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    // ========================================================================
    //  管理画面に出す情報
    // ========================================================================

    /** バックアップ 1 件分の表示情報。 */
    public record BackupInfo(String name, String sizeLabel, String createdLabel) {
    }

    /** 保存先にあるバックアップの一覧（新しい順）。 */
    public List<BackupInfo> listBackups() {
        Path root = Paths.get(properties.dir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<BackupInfo> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(p -> DIR_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(p -> result.add(new BackupInfo(
                            p.getFileName().toString(),
                            formatBytes(directorySize(p)),
                            formatDirName(p.getFileName().toString()))));
        } catch (IOException e) {
            log.warn("バックアップ一覧の取得に失敗しました", e);
        }
        return result;
    }

    public synchronized String getLastMessage() {
        return lastMessage;
    }

    public synchronized boolean isLastFailed() {
        return lastFailed;
    }

    public synchronized LocalDateTime getLastSuccessAt() {
        return lastSuccessAt;
    }

    public synchronized LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    /** 保存先の絶対パス（画面に「どこにあるか」を出す用）。 */
    public String getBackupDirPath() {
        return Paths.get(properties.dir()).toAbsolutePath().normalize().toString();
    }

    /** いまの DB がアプリ内バックアップに対応しているか（= H2 か）。 */
    public boolean isBackupSupported() {
        try {
            return isH2();
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    private boolean withinMinInterval() {
        synchronized (this) {
            if (lastSuccessAt == null) {
                return false;
            }
            return Duration.between(lastSuccessAt, LocalDateTime.now()).toMinutes()
                    < properties.minIntervalMinutes();
        }
    }

    /**
     * いちばん新しいバックアップの取得日時（フォルダ名から読み取る）。1 つも無ければ null。
     * フォルダの更新日時ではなく名前を見るのは、コピーや解凍で日時が変わっても
     * 判定がブレないようにするため。
     */
    private LocalDateTime newestBackupTime() {
        Path root = Paths.get(properties.dir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> DIR_PATTERN.matcher(name).matches())
                    .max(Comparator.naturalOrder())
                    .map(name -> LocalDateTime.parse(name.substring(0, 15), DIR_FORMAT))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("バックアップ履歴の確認に失敗しました", e);
            return null;
        }
    }

    private boolean isH2() {
        return databaseProductName().toLowerCase().contains("h2");
    }

    private String databaseProductName() {
        String name = jdbcTemplate.execute(
                (ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        return name != null ? name : "unknown";
    }

    /**
     * 新しいバックアップのフォルダ名を決める。
     *
     * <p>満たすべき条件は 2 つあります。
     * <ol>
     *   <li>既存と重複しない（上書きしない）</li>
     *   <li><b>名前の並び順で、既存のどれよりも必ず「新しい」側に来る</b></li>
     * </ol>
     *
     * <p>②が必要な理由：世代の間引き（{@link #prune}）は名前の降順で
     * 「新しい方から keep 件残す」と判断します。同じ秒に連続でバックアップし、
     * 間引きで空いた名前をそのまま再利用すると、
     * <b>いま作ったばかりのバックアップが「一番古い」と誤判定されて
     * 即座に削除される</b>という事故が起きます（実際にテストで検出）。
     * そこで、単に空いている名前ではなく
     * 「既存の最大名より大きくなるまで」枝番を進めます。
     * 枝番は -02 のようにゼロ埋めし、-10 以降でも文字列順が崩れないようにしています。
     */
    private Path uniqueDir(Path root, String baseName) throws IOException {
        String maxExisting;
        try (Stream<Path> children = Files.list(root)) {
            maxExisting = children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> DIR_PATTERN.matcher(name).matches())
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        }

        String name = baseName;
        int n = 2;
        while (Files.exists(root.resolve(name))
                || (maxExisting != null && name.compareTo(maxExisting) <= 0)) {
            if (n > 99) {
                // ここに来るのは、PC の時計が過去に巻き戻っているなど異常時だけ。
                // 無限ループするより、はっきり失敗して気づいてもらう。
                throw new IOException("バックアップ名を決められませんでした。"
                        + "PC の時計が正しいか確認してください（既存の最新: " + maxExisting + "）");
            }
            name = baseName + "-" + String.format("%02d", n);
            n++;
        }
        return root.resolve(name);
    }

    /**
     * ディレクトリの中身を 1 つの zip にまとめる。
     *
     * @return zip に入れたファイル数。0 のときは zip 自体を作らない
     */
    private int zipDirectory(Path sourceDir, Path zipFile) {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("画像フォルダの読み取りに失敗しました", e);
        }
        if (files.isEmpty()) {
            return 0;
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Path file : files) {
                // zip の中のパス区切りは OS に関係なく「/」と決められている
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("画像の zip 作成に失敗しました", e);
        }
        return files.size();
    }

    /**
     * 古い世代を削除して、最新 {@code keep} 世代だけ残す。
     *
     * @return 削除した世代数
     */
    private int prune(Path root) throws IOException {
        List<Path> backups;
        try (Stream<Path> children = Files.list(root)) {
            backups = children.filter(Files::isDirectory)
                    .filter(p -> DIR_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        int pruned = 0;
        for (int i = properties.keep(); i < backups.size(); i++) {
            deleteRecursively(backups.get(i));
            pruned++;
        }
        return pruned;
    }

    /** 2次コピー先へフォルダごと複製する。失敗しても本体のバックアップは成功扱いにする。 */
    private String copyToExtraDir(Path source) {
        if (!properties.hasExtraCopy()) {
            return "";
        }
        try {
            Path extraRoot = Paths.get(properties.extraCopyDir()).toAbsolutePath().normalize();
            Path dest = extraRoot.resolve(source.getFileName());
            Files.createDirectories(dest);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    Path target = dest.resolve(source.relativize(path));
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return "、2次コピー済み";
        } catch (Exception e) {
            // USB が抜かれている・OneDrive が落ちている等は普通に起きる。
            // 本体のバックアップは成功しているので、警告に留める。
            log.warn("2次コピーに失敗しました（本体のバックアップは成功しています）: {}", e.getMessage());
            return "、⚠2次コピー失敗（" + e.getMessage() + "）";
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            // 深いものから先に消す（中身が残っているディレクトリは消せないため）
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private long directorySize(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024.0);
        }
        return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
    }

    /** 「20260817-043000」→「2026-08-17 04:30」のような表示に変える。 */
    private static String formatDirName(String name) {
        if (name.length() < 15) {
            return name;
        }
        return "%s-%s-%s %s:%s".formatted(
                name.substring(0, 4), name.substring(4, 6), name.substring(6, 8),
                name.substring(9, 11), name.substring(11, 13));
    }
}
