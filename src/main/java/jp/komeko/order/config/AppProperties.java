package jp.komeko.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml の {@code app:} 以下をまとめて受け取る設定クラス。
 *
 * <p>{@code @Value("${app.base-url}")} を毎回書くより、こうして 1 つのクラスに
 * まとめておくほうが「どんな設定項目があるか」が一目で分かります。
 *
 * <p>Java 16 以降の <b>record</b> を使っています。record は
 * 「値を持つだけの不変クラス」を 1 行で定義できる構文で、
 * フィールド・コンストラクタ・getter・equals・hashCode・toString が自動生成されます。
 * getter は {@code getBaseUrl()} ではなく {@code baseUrl()} という名前になります。
 *
 * @param baseUrl              QR に埋め込む URL のベース（例: http://192.168.0.10:8080）
 * @param uploadDir            商品画像の保存ディレクトリ
 * @param seedOnStartup        起動時にサンプルデータを投入するか
 * @param initialAdminUsername 初回起動時に作る管理者のユーザー名
 * @param initialAdminPassword 初回起動時に作る管理者の初期パスワード
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        String uploadDir,
        boolean seedOnStartup,
        String initialAdminUsername,
        String initialAdminPassword
) {
    /** 末尾のスラッシュを取り除いたベース URL を返す（URL を組み立てるときの事故防止）。 */
    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8080";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
