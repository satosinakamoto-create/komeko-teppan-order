package jp.komeko.order.web;

import jp.komeko.order.config.AppProperties;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.service.QrCodeService;
import jp.komeko.order.service.TableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Comparator;
import java.util.List;

/**
 * ポートフォリオに載せる <b>QR コード用の、変わらない入口</b>。
 *
 * <p><b>なぜ必要か</b><br>
 * ポートフォリオサイトに QR コードの画像を貼り、
 * 見に来た方が自分のスマホで読めば、実店舗とまったく同じ体験になります。
 * ログインも操作説明も要りません。かざすだけです。
 *
 * <p>ところが卓の QR に入っている URL は {@code /t/{ランダムなトークン}} で、
 * このトークンは卓が作られるたびに新しく生成されます（{@code DiningTable} のコンストラクタ）。
 * 公開デモは無料枠で動いており<b>再起動のたびに DB ごと作り直される</b>ので、
 * トークンは毎日変わります。
 *
 * <pre>
 *   cf435fd2-6792-4aa0-a7f8-4b230b364466   ← あるデプロイのカウンター1
 *   21763a4a-51ed-478d-b06e-1acfb8de93df   ← 次のデプロイの同じ卓
 * </pre>
 *
 * <p>つまり<b>卓の URL をそのまま QR にしてサイトに貼ると、翌朝には死にます。</b>
 * 印刷物と違って貼り替えに気づけないぶん、たちが悪い。
 *
 * <p>そこで「変わらない名前」を 1 つ用意し、
 * 中で<b>そのとき空いている卓へ橋渡し</b>します。
 * QR に焼くのはこちらの URL なので、中身が変わっても外から見た入口は変わりません。
 *
 * <pre>
 *   GET /demo  →  空いている卓を選ぶ  →  redirect /t/{その卓のトークン}
 * </pre>
 *
 * <p><b>実店舗には存在しません。</b>
 * {@code @ConditionalOnProperty} を付けてあるので、
 * {@code app.guest-login} が有効なとき（＝公開デモ）だけ Bean が作られます。
 * 無効な環境では URL を直接叩いても 404 です。
 * 画面から隠すだけでは、URL を知っている人には通ってしまいます。
 */
@Controller
@ConditionalOnProperty(name = "app.guest-login", havingValue = "true")
public class DemoEntryController {

    private static final Logger log = LoggerFactory.getLogger(DemoEntryController.class);

    /**
     * 撮影・見学のために空けてある卓。ここが空いていれば優先して使う。
     *
     * <p>{@code DemoDataSeeder} が「この卓だけは伝票を作らない」としているものと同じ名前です。
     * 片方だけ変えると、見学者がいきなり相席から始まることになります。
     */
    private static final String PREFERRED_TABLE = "カウンター1";

    private final TableService tableService;
    private final QrCodeService qrCodeService;
    private final AppProperties appProperties;

    public DemoEntryController(TableService tableService,
                               QrCodeService qrCodeService,
                               AppProperties appProperties) {
        this.tableService = tableService;
        this.qrCodeService = qrCodeService;
        this.appProperties = appProperties;
    }

    @GetMapping("/demo")
    public String enter() {
        DiningTable table = pickTable();
        log.info("見学用の入口から入りました: 卓={}", table.getName());
        return "redirect:/t/" + table.getAccessToken();
    }

    /**
     * この入口の QR コード画像。
     *
     * <p>ポートフォリオサイトに貼る画像を、<b>手作業で作らずに済ませる</b>ためのものです。
     * 別のツールで生成すると、URL を変えたときに画像の作り直しを忘れます。
     * ここから取れば、中身は必ず {@code app.base-url} と一致します。
     *
     * <p>ダウンロードして使う前提なので、キャッシュは短く。
     * 公開 URL を変えたときに古い画像を配り続けないためです。
     */
    @GetMapping(value = "/demo/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr() {
        String url = appProperties.normalizedBaseUrl() + "/demo";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(qrCodeService.toPngBytes(url, 720, 2));
    }

    /**
     * 案内する卓を選ぶ。
     *
     * <p>優先順位は次のとおりです。
     *
     * <ol>
     *   <li>{@link #PREFERRED_TABLE}（空いていれば）</li>
     *   <li>ほかに伝票が開いていない卓</li>
     *   <li>それも無ければ {@link #PREFERRED_TABLE}（＝相席）</li>
     * </ol>
     *
     * <p><b>全部埋まっていてもエラーにしません。</b>
     * 実店舗でも、同じ卓の QR を 2 人が読めば同じ伝票に入ります。
     * それがこのシステムの仕様（1 卓 1 伝票）なので、
     * 見学者が 2 人同時に来ても、片方だけが門前払いになるより自然です。
     */
    private DiningTable pickTable() {
        List<DiningTable> tables = tableService.activeTables().stream()
                .sorted(Comparator.comparing(DiningTable::getName))
                .toList();

        if (tables.isEmpty()) {
            // 卓が 1 つも無いのは DataSeeder が動いていないときだけ。
            // 黙って別の画面へ飛ばすと原因が分からなくなるので、ここで落とす。
            throw new IllegalStateException(
                    "案内できる卓がありません。DataSeeder が動いているか確認してください");
        }

        return tables.stream()
                .filter(t -> PREFERRED_TABLE.equals(t.getName()))
                .filter(this::isFree)
                .findFirst()
                .or(() -> tables.stream().filter(this::isFree).findFirst())
                .or(() -> tables.stream()
                        .filter(t -> PREFERRED_TABLE.equals(t.getName()))
                        .findFirst())
                .orElse(tables.get(0));
    }

    private boolean isFree(DiningTable table) {
        return tableService.currentSession(table.getId()).isEmpty();
    }
}
