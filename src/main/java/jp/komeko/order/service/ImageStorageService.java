package jp.komeko.order.service;

import jp.komeko.order.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 商品画像のアップロードを扱うサービス。
 *
 * <p><b>セキュリティ上の注意点</b>（ファイルアップロードは事故が起きやすい機能です）
 * <ul>
 *   <li>ファイル名は<b>必ず自前で作り直す</b>。
 *       送られてきた名前をそのまま使うと {@code ../../etc/passwd} のような
 *       パス変形（ディレクトリトラバーサル）で任意の場所に書けてしまう。</li>
 *   <li>拡張子と Content-Type の<b>両方</b>を許可リストで確認する。</li>
 *   <li>保存先は Web の公開ディレクトリではなく専用フォルダにし、
 *       配信は {@code WebConfig} のリソースハンドラ経由に限定する。</li>
 *   <li>サイズ上限は application.yml の {@code spring.servlet.multipart} で設定済み。</li>
 * </ul>
 */
@Service
public class ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    /** 受け付ける拡張子。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    /** 受け付ける Content-Type。 */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** 画像を配信する URL の接頭辞。 */
    public static final String PUBLIC_PREFIX = "/uploads/";

    private final Path uploadDir;

    public ImageStorageService(AppProperties properties) {
        this.uploadDir = Paths.get(properties.uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
            log.info("画像の保存先: {}", uploadDir);
        } catch (IOException e) {
            throw new UncheckedIOException("画像保存ディレクトリを作成できませんでした: " + uploadDir, e);
        }
    }

    public Path getUploadDir() {
        return uploadDir;
    }

    /**
     * 画像を保存し、公開 URL（例: {@code /uploads/ab12....jpg}）を返す。
     *
     * @return 保存した画像の公開パス。ファイルが空なら null
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("画像は JPEG / PNG / WebP のみアップロードできます");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("拡張子が対応していません（jpg / jpeg / png / webp）");
        }

        // ファイル名は完全に自前で作る。元の名前は一切使わない。
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = uploadDir.resolve(storedName).normalize();

        // 念のため、保存先が uploadDir の外に出ていないか確認する
        if (!target.startsWith(uploadDir)) {
            throw new IllegalArgumentException("不正な保存先です");
        }

        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("画像の保存に失敗しました", e);
        }
        log.info("画像を保存しました: {}", storedName);
        return PUBLIC_PREFIX + storedName;
    }

    /**
     * 画像を削除する。存在しなくてもエラーにしない。
     *
     * @param publicPath {@code /uploads/xxxx.jpg} 形式のパス
     */
    public void delete(String publicPath) {
        if (publicPath == null || !publicPath.startsWith(PUBLIC_PREFIX)) {
            return;
        }
        String fileName = publicPath.substring(PUBLIC_PREFIX.length());
        // ここでも念のためパス変形を弾く
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return;
        }
        Path target = uploadDir.resolve(fileName).normalize();
        if (!target.startsWith(uploadDir)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("画像の削除に失敗しました: {}", fileName, e);
        }
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
