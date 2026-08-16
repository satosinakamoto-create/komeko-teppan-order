package jp.komeko.order.web.admin;

import jp.komeko.order.config.AppProperties;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.service.QrCodeService;
import jp.komeko.order.service.TableService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卓に貼る QR コードの表示・ダウンロード・印刷（管理者のみ）。
 *
 * <p><b>テイクアウトのときとの違い</b><br>
 * 以前は「店に 1 枚だけ QR を貼る」形でした。イートインに作り替えたいま、
 * QR は<b>卓ごとに 1 枚</b>になります。お客さんが読んだ QR がそのまま
 * 「どの席から注文したか」を表すので、QR と席は 1 対 1 でなければいけません。
 * 3番テーブルに 5番テーブルの QR を貼ってしまうと、料理が別の席に運ばれます。
 *
 * <p><b>QR に入っている文字列</b><br>
 * {@code {app.base-url}/t/{その卓の accessToken}} です。
 * {@code accessToken} は卓ごとのランダムな文字列で、
 * 「その QR を実際に読んだ人だけがその席の伝票を触れる」ようにするための鍵です
 * （詳しくは {@link DiningTable} のコメント）。
 *
 * <p>URL の組み立てをこのクラスで行っているのは、
 * {@link QrCodeService#orderPageUrl(String)} が「店のトップページ」用であり、
 * 卓ごとの URL とは用途が違うためです。
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
    private final TableService tableService;
    private final AppProperties appProperties;

    public AdminQrController(QrCodeService qrCodeService,
                             TableService tableService,
                             AppProperties appProperties) {
        this.qrCodeService = qrCodeService;
        this.tableService = tableService;
        this.appProperties = appProperties;
    }

    // ========================================================================
    //  一覧（画面確認用）
    // ========================================================================

    /**
     * 全卓の QR を並べて見せる確認ページ。
     *
     * <p>印刷する前に、必ずここで<b>飛び先の URL</b>を目で確認してください。
     * {@code localhost} のままだとお客さんのスマホからは開けません。
     */
    @GetMapping
    public String page(@RequestParam(required = false) Integer size, Model model) {
        // 利用停止中の卓も含めて出す。「なぜこの席の QR が無いのか」を
        // 画面上で確認できたほうが、卓の登録漏れに気付きやすいため。
        List<DiningTable> tables = tableService.allTables();

        model.addAttribute("activeNav", "admin");
        model.addAttribute("tables", tables);
        model.addAttribute("tableUrls", buildTableUrls(tables));
        model.addAttribute("size", clampSize(size));
        // 設定ファイルの値をそのまま画面に出して、書き換え忘れに気付けるようにする
        model.addAttribute("baseUrl", appProperties.normalizedBaseUrl());
        model.addAttribute("printableCount", tables.stream().filter(DiningTable::isActive).count());
        return "admin/qr";
    }

    // ========================================================================
    //  PNG 画像
    // ========================================================================

    /**
     * 指定した卓の QR コードの PNG 画像そのものを返す。
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
     * ファイル名に卓名（日本語）を入れないのは、
     * HTTP ヘッダーに非 ASCII 文字をそのまま書けず、
     * 環境によって文字化けやエラーになるためです。
     */
    @GetMapping(value = "/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam("tableId") Long tableId,
                                        @RequestParam(required = false) Integer size) {
        DiningTable table;
        try {
            table = tableService.getById(tableId);
        } catch (TableService.TableNotFoundException e) {
            // 画像を返す URL なので、エラーページ（HTML）を返しても <img> の中では読めません。
            // 中身の無い 404 を返し、ブラウザには「画像が壊れている」と表示させます。
            return ResponseEntity.notFound().build();
        }

        byte[] png = qrCodeService.toPngBytes(tableUrl(table), clampSize(size), QUIET_ZONE);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"komeko-qr-table-%d.png\"".formatted(table.getId()))
                // base-url を変えたのに古い QR が表示される、
                // QR を再発行したのに古い QR が出る、という事故を防ぐためキャッシュさせない
                .cacheControl(CacheControl.noCache())
                .body(png);
    }

    // ========================================================================
    //  印刷用ポップ
    // ========================================================================

    /**
     * 卓ごとの卓上ポップをまとめて印刷する。
     *
     * <p>1 卓につき 1 枚（{@code .print-sheet}）並べます。app.css の
     * {@code @media print} に {@code page-break-after: always} を入れてあるので、
     * ブラウザの印刷でそのまま卓の数だけ改ページされます。
     *
     * <p>ヘッダーやナビが写り込まないよう、レイアウトは {@code layout/plain} を使います。
     * 印刷時に隠したい要素には {@code .no-print} を付けてあります。
     *
     * <p><b>利用停止中の卓は含めません。</b>
     * その卓の QR を読んでも「ただいまご利用いただけません」と出るだけで、
     * 貼っても意味が無い（むしろお客さまを混乱させる）ためです。
     */
    @GetMapping("/print")
    public String print(@RequestParam(required = false) Integer size, Model model) {
        List<DiningTable> tables = tableService.activeTables();

        model.addAttribute("tables", tables);
        model.addAttribute("tableUrls", buildTableUrls(tables));
        model.addAttribute("size", clampSize(size));
        model.addAttribute("baseUrl", appProperties.normalizedBaseUrl());
        return "admin/qr-print";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * その卓の QR に埋め込む URL を組み立てる。
     *
     * <p>{@code normalizedBaseUrl()} は末尾のスラッシュを落としてくれるので、
     * {@code http://192.168.0.10:8080//t/xxxx} のようにスラッシュが
     * 二重になる事故を防げます。
     *
     * <p>{@code accessToken} は UUID（英数字とハイフンだけ）なので、
     * URL エンコードは不要です。
     */
    private String tableUrl(DiningTable table) {
        return appProperties.normalizedBaseUrl() + "/t/" + table.getAccessToken();
    }

    /**
     * 「卓の id → QR の URL」の対応表を作る。
     *
     * <p>テンプレートからは {@code ${tableUrls.get(t.id)}} の形で引けます。
     * Thymeleaf の式の中で文字列を連結して URL を組み立てると、
     * 画面ごとに書き方がずれて事故のもとになるので、
     * <b>URL を作る場所は Java 側の 1 か所</b>にまとめています。
     *
     * <p>{@code LinkedHashMap} は「入れた順番」を保つ Map です。
     * ここでは順番に意味はありませんが、デバッグで中身を覗いたときに
     * 卓の並び順どおりに出るほうが読みやすいので使っています。
     */
    private Map<Long, String> buildTableUrls(List<DiningTable> tables) {
        Map<Long, String> urls = new LinkedHashMap<>();
        for (DiningTable table : tables) {
            urls.put(table.getId(), tableUrl(table));
        }
        return urls;
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
