package jp.komeko.order.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 在庫・仕入れモジュールの設定（{@code app.inventory:} 以下）。
 *
 * <p><b>{@code enabled} は非常口です。</b>
 * false にすると、このモジュールの画面は<b>そもそも組み込まれず</b>、
 * URL ごと存在しなくなります（{@code @ConditionalOnProperty}）。
 * 本番で何か起きたときに、コードを書き換えて再ビルドするのではなく
 * 設定 1 行と再起動で従来の姿に戻せるようにするためのものです。
 *
 * @param enabled            モジュールを有効にするか（既定 false）
 * @param inputDeadlineDays  受領から何日以内の登録なら期限内とみなすか
 * @param rateWarningDays    税率・控除率マスタの期限切れを何日前から警告するか
 * @param minImagePixels     レシート画像に求める最低画素数（電子帳簿保存法の 200dpi 相当）
 * @param ocr                AI 読取の設定
 */
@ConfigurationProperties(prefix = "app.inventory")
public record InventoryProperties(
        boolean enabled,
        int inputDeadlineDays,
        int rateWarningDays,
        long minImagePixels,
        Ocr ocr
) {

    /**
     * 既定値を埋めるコンパクトコンストラクタ。
     *
     * <p>record は「設定を書かなかった項目が 0 や null になる」ので、
     * 意味のある既定をここで入れておきます。
     */
    public InventoryProperties {
        if (inputDeadlineDays <= 0) {
            // 電子帳簿保存法の「速やか」は概ね 7 営業日。
            // 営業日は店の定休日に左右されて厳密に数えにくいので、
            // より厳しい側（暦日 7 日）で警告する。早く出るぶんには害がない。
            inputDeadlineDays = 7;
        }
        if (rateWarningDays <= 0) {
            rateWarningDays = 90;
        }
        if (minImagePixels <= 0) {
            // レシート（幅 58〜80mm）を 200dpi で読むと、幅は 460〜630px 程度。
            // 長さを 3 倍と見ても 100 万画素あれば足りる。
            // A4 の書類まで受けるなら 387 万画素が要るが、それは将来の話。
            minImagePixels = 1_000_000L;
        }
        if (ocr == null) {
            ocr = new Ocr(null, null, 0);
        }
    }

    /**
     * レシート読取に使う AI の設定。
     *
     * @param apiKey    Anthropic の API キー。空なら読取機能は無効（手入力運用になる）
     * @param model     使うモデル
     * @param timeoutMs 応答を待つ上限（ミリ秒）
     */
    public record Ocr(String apiKey, String model, int timeoutMs) {
        public Ocr {
            if (model == null || model.isBlank()) {
                model = "claude-opus-5";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 120_000;
            }
        }

        /** API キーが設定されているか。false なら読取ボタンを出さない。 */
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
