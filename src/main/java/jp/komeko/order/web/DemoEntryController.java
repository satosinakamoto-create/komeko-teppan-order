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
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
    public String enter(Model model) {
        // ── 起動直後の空白時間を「準備中」ページで受ける ──────────────
        //
        // 無料枠のコールドスタートでは、HTTP の受付が始まってから
        // DemoDataSeeder が卓を入れ終わるまでに数十秒の空白がある。
        // 以前はこの間に来た人へ IllegalStateException を投げていたため、
        // ポートフォリオの主導線が
        //   409「案内できる卓がありません。DataSeeder が動いているか確認してください」
        // という開発者向けの行き止まりになっていた（2026-08-22 に実測で再現）。
        // 眠りから覚めるのを 2〜3 分待ってくれた人に最初に見せる画面がこれでは、
        // その場で閉じられて終わる。
        //
        // いまは自動で再読み込みする案内ページを返す。数十秒後の再読み込みで
        // 卓が入っていれば、そのまま何事もなく注文画面へ進む。
        if (tableService.activeTables().isEmpty()) {
            log.info("卓がまだ無いので、準備中ページを返しました（シーダーの完了待ち）");
            return "demo-preparing";
        }

        // ── 押してから進む（2026-09-23・設計 暗31 1597:16742）──────────
        //
        // ここは長いあいだ、卓の画面へそのまま転送していました。
        // すぐ下の staffEntry の注釈に「客側は /demo を開けばそのまま
        // 注文画面に入れますが」と書いてあるとおりです。
        //
        // ですが飛ばされた人は、人数を選ぶボタンが目の前にある状態から始まります。
        // そこには「これは本物の注文ではない」と書いてありません。
        // 店舗側ではわざわざ 1 クリック足してまで伝えていることを、
        // お客さま側では伝えていませんでした。手数ではなく、非対称のほうが問題です。
        //
        // ★ 転送をやめても「実在する空いている卓を選ぶ」ところは変えていません。
        //   選んだ卓のトークンを画面に渡し、ボタンのリンク先にします。
        //   DemoEntryTest はそこを見ています（撮影用の卓を優先する・
        //   埋まっていたら別の卓へ、という判断は前のまま）。
        DiningTable table = pickTable();
        log.info("見学用の入口を出しました: 卓={}", table.getName());
        model.addAttribute("tableToken", table.getAccessToken());
        // ★ 人数は卓の定員で埋める。見学者に「何名さまですか」と尋ねても
        //   答えようがないので、デモでは聞かない（店主の指示）。
        //   送り先は実店舗と同じ /t/{token}/start なので、
        //   伝票の開き方もテーブルチャージの計算も本番と同じ経路を通る。
        model.addAttribute("guestCount", table.getCapacity());
        return "demo-guest";
    }

    /**
     * 店舗側の見学入口。ポートフォリオからここへ来ます。
     *
     * <p>客側は {@code /demo} を開けばそのまま注文画面に入れますが、
     * 店舗側は {@code /login} を開いて「ゲストで参加する」を押す必要がありました。
     * 片方だけ手数が多いのは、並べて紹介するときに座りが悪い。
     *
     * <hr>
     *
     * <h2>手数を減らすより、何の画面かを先に伝える</h2>
     *
     * <p>最初は<b>開いた瞬間にフォームを送信する</b>作りにして、
     * 押す手間そのものを無くしていました。ですが実際に通してみると、
     * 押していないのに厨房ボードが出るため、
     * <b>何が起きたのか分からないまま画面が切り替わります。</b>
     * 見学モードであることも、データが架空であることも伝わりません。
     *
     * <p>そこでいまは<b>説明を出して、押してから進む</b>形にしています。
     * 1 クリック増えますが、その 1 画面で
     * 「厨房のタブレットで開いている画面」「表示のみ」「データは架空」を先に伝えられます。
     * 何を見ればいいか分からないまま入るより、そのほうが速く伝わります。
     *
     * <hr>
     *
     * <h2>なぜ「GET でログインさせる」ではないのか</h2>
     *
     * <p>手っ取り早いのは {@code GET /demo/staff} でそのままログインさせることです。
     * ですがそれをやると、外部サイトに
     * {@code <img src="…/demo/staff">} と書かれるだけで、
     * 見た人が意図せずログイン状態になります。
     * ゲストログインを POST ＋ CSRF にしているのは、まさにこれを塞ぐためでした。
     * 入口を増やすために、その塞ぎ穴を自分で開け直すのは筋が悪い。
     *
     * <ul>
     *   <li>送信は POST ＋ CSRF トークン。ログイン画面のボタンと同じ仕組み</li>
     *   <li>{@code <img>} で読み込まれても、返るのは HTML なので何も起きない</li>
     *   <li>iframe に入れられても、Spring Security の既定で
     *       {@code X-Frame-Options: DENY} が付くため描画されない</li>
     * </ul>
     */
    @GetMapping("/demo/staff")
    public String staffEntry(@RequestParam(name = "retry", required = false) String retry,
                             Model model) {
        // ★ キャッシュ禁止をここで書かないのは、既に付いているからです。
        //
        //   一度 Cache-Control: no-store を自分で付けましたが、本番のヘッダを見たら
        //   Spring Security が元から
        //     Cache-Control: no-cache, no-store, max-age=0, must-revalidate
        //     Pragma: no-cache / Expires: 0
        //   を全レスポンスに付けていました。しかも自分で 1 つでも書くと、
        //   Spring Security は 3 つまとめて書き込みを飛ばします。
        //   つまり足したつもりが、既定より弱くしていました。
        //
        //   ここから分かるのは、403 の原因はキャッシュではなかったということです。
        //   この画面のトークンが古くなるのは、
        //     ・ログインでセッション ID が変わったあと、戻るボタンで履歴から再表示された
        //     ・無料枠でインスタンスが入れ替わり、送信先にそのセッションが無かった
        //   のどちらかです。どちらも防ぎようがないので、
        //   下の「失敗しても行き止まりにしない」ほうが本命の対処になります。

        // 一度失敗して戻ってきたときだけ、その理由を出します。
        model.addAttribute("retryMode", retry != null);
        return "demo-staff";
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

        // ★ 探す順番。片付け待ちの卓は<b>いちばん最後</b>に置くこと。
        //
        //   1. 撮影用に空けてある卓（カウンター1）が使えるなら、そこ
        //   2. 使える卓ならどこでも
        //   3. 誰かが座っている卓（相席。1卓1伝票なので実店舗でも起きる）
        //   4. それも無ければ先頭
        //
        //   3 を 4 より先に置くのが今回の肝です。相席は実店舗でも起きる正常な
        //   状態ですが、片付け待ちの卓は注文そのものが弾かれます。
        //   「満席だから相席」は案内になりますが、
        //   「片付け待ちなのでスタッフにお声がけください」は、
        //   スタッフのいない見学者にとって行き止まりです。
        return tables.stream()
                .filter(t -> PREFERRED_TABLE.equals(t.getName()))
                .filter(this::isFree)
                .findFirst()
                .or(() -> tables.stream().filter(this::isFree).findFirst())
                .or(() -> tables.stream()
                        .filter(t -> PREFERRED_TABLE.equals(t.getName()))
                        .filter(this::hasOpenSession)
                        .findFirst())
                .or(() -> tables.stream().filter(this::hasOpenSession).findFirst())
                .orElse(tables.get(0));
    }

    /** 誰かが座っている（伝票が開いている）卓か。相席になるが、注文はできる。 */
    private boolean hasOpenSession(DiningTable table) {
        return tableService.currentSession(table.getId()).isPresent();
    }

    /**
     * 見学者をそこへ案内してよい卓か。
     *
     * <p><b>「伝票が無い」だけでは足りません（2026-09-23 に踏みました）。</b>
     * 会計が済んだ卓は片付け待ち（{@code needsCleanup}）として残り、
     * この状態で注文を始めようとすると {@code TableNotReadyException} で弾かれます。
     *
     * <pre>
     *   見学用の入口を出しました: 卓=テーブル1
     *   Resolved [TableNotReadyException:
     *     「テーブル1」は片付け待ちです。お手数ですがスタッフにお声がけください]
     * </pre>
     *
     * <p>ポートフォリオから来た人が最初に押すボタンで、この文面が出ます。
     * 「スタッフにお声がけください」と言われても、見学者の前にスタッフはいません。
     * <b>行き止まりです。</b>
     *
     * <p>入口の画面が出た時点では何も起きないので、
     * <b>押すまで壊れていることが分かりません</b>——選ぶ側で弾くしかありません。
     * CLAUDE.md の「お客さま側を撮る卓は『使える・片付け済み・伝票なし』の
     * 3 条件で選ぶ」と同じ話です。
     */
    private boolean isFree(DiningTable table) {
        return !table.isNeedsCleanup()
                && tableService.currentSession(table.getId()).isEmpty();
    }
}
