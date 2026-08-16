package jp.komeko.order.web.admin;

import jp.komeko.order.service.QrCodeService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 店頭に貼る QR コードの表示・ダウンロード・印刷（管理者のみ）。
 *
 * <p>QR に入っているのは「メニューの URL」そのものです。
 * お客さんはこれを読み取るだけで注文画面に入れます。会員登録もアプリも要りません。
 *
 * <p><b>{@code ?src=} パラメータ</b><br>
 * 「カウンターの POP」「テイクアウトの袋」など、貼った場所ごとに違う値を入れておくと、
 * あとから「どこ経由の注文が多いか」を調べられます。省略しても構いません。
 */
@Controller
@RequestMapping("/admin/qr")
public class AdminQrController {

    /**
     * 画像サイズの許容範囲。
     * 小さすぎるとスマホが読めず、大きすぎると生成に時間がかかって
     * サーバへの負荷攻撃（?size=99999 を連打される等）に使われかねないので、
     * 受け取った値は必ずこの範囲に丸めます。
     */
    private static final int MIN_SIZE = 256;
    private static final int MAX_SIZE = 1024;
    private static final int DEFAULT_SIZE = 512;

    /** QR の周囲に取る余白（モジュール数）。印刷して読ませるなら 4 以上が安全。 */
    private static final int QUIET_ZONE = 4;

    private final QrCodeService qrCodeService;

    public AdminQrController(QrCodeService qrCodeService) {
        this.qrCodeService = qrCodeService;
    }

    /** QR の確認ページ。飛び先 URL の確認と、印刷・ダウンロードへの入口。 */
    @GetMapping
    public String page(@RequestParam(required = false) String src,
                       @RequestParam(required = false) Integer size,
                       Model model) {
        addQrAttributes(model, src, size);
        model.addAttribute("activeNav", "admin");
        return "admin/qr";
    }

    /**
     * QR コードの PNG 画像そのものを返す。
     *
     * <p><b>{@code ResponseEntity<byte[]>} とは</b><br>
     * 「画面（HTML）ではなく、ステータス・ヘッダー・本文を自分で組み立てて返したい」
     * ときに使う戻り値です。ここでは本文がバイト列（PNG）になります。
     *
     * <p><b>Content-Disposition</b><br>
     * {@code inline} はブラウザ内に表示、{@code attachment} は即ダウンロードを意味します。
     * ここでは {@code <img>} タグからも参照するので {@code inline} にし、
     * ファイル名だけ添えておきます。画面側の
     * {@code <a download="...">} を押したときはブラウザが保存に切り替えてくれます。
     */
    @GetMapping(value = "/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam(required = false) String src,
                                        @RequestParam(required = false) Integer size) {
        String url = qrCodeService.orderPageUrl(src);
        byte[] png = qrCodeService.toPngBytes(url, clampSize(size), QUIET_ZONE);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"komeko-order-qr.png\"")
                // base-url を変えたのに古い QR が表示される、という事故を防ぐためキャッシュさせない
                .cacheControl(CacheControl.noCache())
                .body(png);
    }

    /**
     * A4 に貼れる印刷用ポップ。
     *
     * <p>ヘッダーやナビが写り込まないよう、レイアウトは {@code layout/plain} を使います。
     * 印刷時に隠したい要素には {@code .no-print} を付けてあります（app.css で対応済み）。
     */
    @GetMapping("/print")
    public String print(@RequestParam(required = false) String src,
                        @RequestParam(required = false) Integer size,
                        Model model) {
        addQrAttributes(model, src, size);
        return "admin/qr-print";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /** 3 つの画面で共通して使う値をまとめてモデルへ。 */
    private void addQrAttributes(Model model, String src, Integer size) {
        String normalizedSrc = (src == null) ? "" : src.trim();
        model.addAttribute("src", normalizedSrc);
        model.addAttribute("size", clampSize(size));
        // QR に埋め込むのと同じ文字列。画面にもそのまま出して、飛び先を目で確認できるようにする
        model.addAttribute("qrUrl", qrCodeService.orderPageUrl(normalizedSrc));
    }

    /**
     * 受け取ったサイズを許容範囲に丸める。
     * 未指定なら既定値。{@code Math.max} と {@code Math.min} を重ねるのが
     * 「範囲に収める」いちばん短い書き方です。
     */
    private int clampSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }
}
