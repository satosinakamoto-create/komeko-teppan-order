package jp.komeko.order.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * バックアップ機能のテスト。
 *
 * <p><b>なぜここを厚くテストするのか</b><br>
 * バックアップは「事故が起きた日」に初めて使われる機能です。
 * ふだん動いているように見えて実は空の zip を作っていた——という失敗は、
 * 気づいたときにはもう取り返しがつきません。
 * だから「本当に中身のあるファイルができること」までテストで確かめます。
 *
 * <p><b>テスト専用の設定について</b><br>
 * 共通の test プロファイルはメモリ DB＋バックアップ無効ですが、
 * このテストだけ {@code @TestPropertySource} で
 * <ul>
 *   <li>DB を一時フォルダの「ファイル DB」に差し替え（H2 の BACKUP TO を本物の形で試すため）</li>
 *   <li>バックアップを有効化し、出力先も一時フォルダへ</li>
 * </ul>
 * しています。設定が違うので Spring は専用のコンテキストをもう 1 つ起動します
 * （その分このテストは少し遅い。それだけの価値がある場所に絞って使うテクニックです）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:${java.io.tmpdir}/komeko-backup-test/db;AUTO_SERVER=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.backup.enabled=true",
        "app.backup.backup-on-shutdown=false",
        // 起動時の追いつきバックアップも止める。動いたままだと
        // 「テストが作ったのではないバックアップ」が混ざり、件数の検証が不安定になる
        "app.backup.backup-on-startup=false",
        "app.backup.dir=${java.io.tmpdir}/komeko-backup-test/backups",
        "app.backup.keep=3",
        "app.upload-dir=${java.io.tmpdir}/komeko-backup-test/uploads"
})
class BackupServiceIntegrationTest {

    private static final Path BASE =
            Paths.get(System.getProperty("java.io.tmpdir"), "komeko-backup-test");
    private static final Path BACKUP_DIR = BASE.resolve("backups");
    private static final Path UPLOAD_DIR = BASE.resolve("uploads");

    @Autowired
    private BackupService backupService;

    @Autowired
    private MockMvc mockMvc;

    /**
     * 前回のテスト実行が残したバックアップを片付けてから始める。
     * DB ファイル本体は Spring が接続を開いている最中なので触らない
     * （バックアップ出力だけを消せば、テストの独立性は保てる）。
     */
    @BeforeAll
    static void cleanupPreviousRuns() throws IOException {
        deleteRecursively(BACKUP_DIR);
    }

    @Test
    @DisplayName("バックアップを取ると、DB の zip と画像の zip が実際に作られる")
    void backupCreatesRealFiles() throws IOException {
        // 画像フォルダにダミーの「商品写真」を 1 枚置いておく
        Files.createDirectories(UPLOAD_DIR);
        Files.writeString(UPLOAD_DIR.resolve("dummy.jpg"), "not-really-a-jpeg");

        String message = backupService.backupNow("テスト");

        assertThat(message).contains("成功");

        Path newest = newestBackupDir();
        // DB の zip：存在するだけでなく「中身が空でない」ことまで見る。
        // 空の zip でも「ファイルはある」ので、サイズを見ないと偽の安心になる。
        Path dbZip = newest.resolve("database.zip");
        assertThat(dbZip).exists();
        assertThat(Files.size(dbZip)).isGreaterThan(0);

        // 画像の zip
        assertThat(newest.resolve("uploads.zip")).exists();
    }

    @Test
    @DisplayName("keep(3世代) を超えた古いバックアップは自動で削除される")
    void oldBackupsArePruned() throws IOException {
        // 5 回続けて取る（同じ秒でもフォルダ名に -2, -3 が付いて衝突しない）
        for (int i = 0; i < 5; i++) {
            backupService.backupNow("テスト");
        }
        assertThat(countBackupDirs()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("管理画面のバックアップページが ADMIN 権限で表示できる")
    @WithMockUser(roles = "ADMIN")
    void backupPageRendersForAdmin() throws Exception {
        // 画面が 200 を返す＝テンプレートの式が全部評価できた、という確認。
        // Thymeleaf の書き間違い（存在しない getter の参照）はこれで捕まる。
        mockMvc.perform(get("/admin/backups"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("バックアップページは STAFF 権限では開けない（403）")
    @WithMockUser(roles = "STAFF")
    void backupPageForbiddenForStaff() throws Exception {
        // バックアップの保存先パスなどサーバ内部の情報が見えるページなので、
        // 店長（ADMIN）だけに限定していることを確認する
        mockMvc.perform(get("/admin/backups"))
                .andExpect(status().isForbidden());
    }

    // ── ヘルパー ─────────────────────────────────────────────

    private Path newestBackupDir() throws IOException {
        try (Stream<Path> children = Files.list(BACKUP_DIR)) {
            return children.filter(Files::isDirectory)
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("バックアップフォルダが作られていません"));
        }
    }

    private long countBackupDirs() throws IOException {
        try (Stream<Path> children = Files.list(BACKUP_DIR)) {
            return children.filter(Files::isDirectory).count();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
