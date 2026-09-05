package jp.komeko.order.seed;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>画面録画・スクリーンショット用</b>のデモデータを入れる。
 *
 * <p>ポートフォリオに載せる録画を撮るとき、
 * 何も無い画面は「動くところ」が見えません。
 * 厨房ボードが空、在庫の残数もゼロ件、では
 * せっかく作った機能がひとつも映らないからです。
 *
 * <p>そこで<b>いかにも営業中</b>の状態を作ります。
 * ただし<b>撮影で使う卓（カウンター1）だけは空けておきます</b>。
 * そこへ実際にスマホで QR を読み、注文が厨房へ飛ぶところを撮るためです。
 *
 * <p><b>使い方</b>
 * <pre>
 *   .\tools\run.ps1 -Demo
 * </pre>
 *
 * <p><b>安全装置</b><br>
 * このクラスは 2 つの条件がそろったときだけ動きます。
 * <ol>
 *   <li>{@code dev}（手元）か {@code demo}（公開デモ）のプロファイル</li>
 *   <li>設定 {@code app.demo-data} が {@code true}
 *       （手元は {@code run.ps1 -Demo}、公開デモは demo プロファイルが渡します）</li>
 * </ol>
 * <b>本番（{@code prod}）ではクラスそのものが読み込まれません。</b>
 * 起動オプションを間違えても、お客さまのデータに架空の注文が混ざることはありません。
 *
 * <p><b>なぜ起動引数ではなく設定値で受けるのか</b><br>
 * 最初は {@code --app.demo-data=true} という起動引数で受けていましたが、
 * Maven 経由で複数の引数を渡すと区切りが解釈されず、
 * ポート番号が {@code "8080,--app.demo-data=true"} という文字列になって起動に失敗しました。
 * 設定値なら環境変数 {@code APP_DEMO_DATA} からそのまま入るので、
 * 途中の道具（Maven / PowerShell）の癖に左右されません。
 *
 * <p><b>何度起動しても「いまの営業日の、営業中らしい状態」に収束します。</b><br>
 * 以前は「開いている伝票が 1 つでもあれば全部見送り」でしたが、
 * それだと<b>前の営業日に入れたデモの伝票が安全装置そのものに引っかかり、
 * 再起動しても二度とデータが入らなくなりました</b>。
 * 実際、5:00（営業日の切り替え）の 15 分前に起動したデモデータが
 * 15 分後にボードから消え、しかも残った伝票のせいで入れ直せない、が起きています。
 * いまは次の 3 段階で、その日ぶんの状態を毎回作り直します。
 * <ol>
 *   <li>前の営業日から残っている OPEN の伝票は、提供を済ませて会計する（片付け）</li>
 *   <li>今の営業日の卓の埋まりが目標に足りなければ、伝票と注文を足す（積み増し）</li>
 *   <li>今の営業日に会計済みの伝票が無ければ、先に帰った組を 2 組作る（本日の売上）</li>
 * </ol>
 * すでに足りている段は何もしないので、同じ日に何度起動しても積み上がりません。
 * 今の営業日にすでに開いている伝票（自分で操作中のものを含む）には触りません。
 *
 * <p><b>{@code @Order(2)} — {@link DataSeeder} の後に走らせる</b><br>
 * このクラスは卓とメニューが<b>すでにある前提</b>で伝票を積みます。
 * 土台を作るのは {@link DataSeeder} なので、必ずあちらが先です。
 *
 * <p>順序を指定していなかったときに、実際に事故が起きました。
 * 手元では偶然 {@link DataSeeder} が先に走っていたのに、
 * Render のコンテナでは<b>逆順になり</b>、卓もメニューも無い状態で
 * このクラスが走って「0 卓ぶんの伝票を作成」で終わり、
 * 公開デモの厨房ボードが空のままになりました。
 * {@link ApplicationRunner} が複数あるとき Spring は実行順を保証しないので、
 * 順番に意味があるなら必ず {@code @Order} で書きます。
 *
 * <p>注釈を {@code import} せず完全修飾で書いているのは、
 * このクラスが注文エンティティ {@link Order} を使っており、
 * 名前が衝突するためです（実際にコンパイルが通りませんでした）。
 */
@Component
@Profile({"dev", "demo"})
@org.springframework.core.annotation.Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** 撮影に使う卓。ここだけは空けておく。 */
    private static final String STAGE_TABLE = "カウンター1";

    private final DiningTableRepository tableRepository;
    private final MenuItemRepository menuItemRepository;
    private final TableService tableService;
    private final CartService cartService;
    private final OrderService orderService;
    private final ShopSettingService shopSettingService;

    /**
     * 注文時刻をずらすためだけに使う。
     *
     * <p>{@code Order} の注文時刻は<b>業務コードから書き換えられないようにしてあります</b>
     * （{@code Order#setCreatedAtForTest} は domain パッケージ内からしか見えない）。
     * 会計の証跡なので、それでよい設計です。
     *
     * <p>ただしデモの注文は全部「たったいま」入ったことになるため、
     * 厨房ボードの経過時間が<b>全部 0 分</b>になります。
     * 厨房ボードでいちばん大事な情報は「何分待たせているか」なので、
     * それが全部 0 分では画面を見ても何も判断できません。
     * <b>このクラスだけ</b>、DB を直接更新して時刻を巻き戻します。
     * dev / demo プロファイルでしか作られないクラスなので、
     * 実店舗のデータにこの操作が届くことはありません。
     */
    private final EntityManager entityManager;

    /** true のときだけデモデータを入れる。既定は false（うっかり動かないように）。 */
    private final boolean enabled;

    public DemoDataSeeder(DiningTableRepository tableRepository,
                          MenuItemRepository menuItemRepository,
                          TableService tableService,
                          CartService cartService,
                          OrderService orderService,
                          ShopSettingService shopSettingService,
                          EntityManager entityManager,
                          @Value("${app.demo-data:false}") boolean enabled) {
        this.tableRepository = tableRepository;
        this.menuItemRepository = menuItemRepository;
        this.tableService = tableService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.shopSettingService = shopSettingService;
        this.entityManager = entityManager;
        this.enabled = enabled;
    }

    /**
     * <p><b>{@code @Transactional} をここ（{@code run}）に付ける理由</b><br>
     * はじめは下の {@code seed()} に付けていましたが、効きませんでした。
     * Spring のトランザクションは<b>ほかのクラスから呼ばれたときにだけ</b>始まります。
     * 同じクラスの中から {@code seed()} を呼ぶと、
     * Spring が用意した「入口の代役（プロキシ）」を通らないので、素通りします。
     *
     * <p>その結果、商品のオプションを読もうとした瞬間に
     * {@code LazyInitializationException}（DB との接続がもう無い）で起動が止まりました。
     * {@code run()} は Spring が外から呼ぶので、こちらに付ければ確実に効きます。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            seed();
        } catch (RuntimeException e) {
            // ★ デモデータの投入に失敗しても、アプリは起動させる。
            //
            // ここを素通しにしていたら、営業時間外に起動したとき
            //   OrderRejectedException: 本日の営業は 17:30 からです。
            // で ApplicationRunner が落ち、アプリごと起動できませんでした。
            //
            // 撮影用のおまけデータが入らないことと、
            // システムが起動しないことは、深刻さがまるで違います。
            // 「あると嬉しい処理」で全体を止めない、が原則です。
            log.warn("デモデータの投入に失敗しました（アプリは通常どおり起動します）: {}", e.toString());
        }
    }

    void seed() {
        openTheShop();
        setUpStock();
        openMarketPriceItems();
        setUpDemoPhotos();

        int cleaned = closeLeftoverSessions();
        int created = fillLiveScene();
        int closed = closeEarlierGuests();

        log.warn("""

                ============================================================
                 デモデータを「営業中」の状態にそろえました。
                   ・前の営業日の伝票を片付け: {} 卓
                   ・営業中の伝票を追加: {} 卓（厨房ボードの 3 列が埋まります）
                   ・会計済みの組を追加: {} 組（本日の売上に数字が出ます）
                   ・時価の品を 3 通りの状態にしました
                       サーロイン ¥3,800 … 当日価格を入れて販売再開（正しい運用）
                       本日の日本酒        … 0 円のまま販売再開（入れ忘れた状態）
                       ワイン類・赤身      … 売り切れのまま（出荷時の状態）
                   ・{} は空けてあります ← ここで QR を読んで注文を試せます

                 片付けたいときは ホール画面（/hall）から会計してください。
                ============================================================
                """, cleaned, created, closed, STAGE_TABLE);
    }

    /**
     * 時刻で受付が止まらないようにする（24 時間受付）。
     *
     * <p>実店舗の営業時間は 17:30〜翌 1:30 です。
     * そのままだと昼間に触った人は<b>何も注文できません</b>。
     * 公開デモは世界中から、どの時間帯にも見に来られるので、
     * 「いまは営業時間外です」と言われて終わるのでは意味がありません。
     *
     * <p>撮影用に手元で使うときも同じです。
     * 実際、営業時間外に起動したらここで注文が弾かれ、
     * その例外でアプリごと起動できなくなりました。
     */
    private void openTheShop() {
        ShopSetting setting = shopSettingService.current();
        if (!setting.isAlwaysOpen()) {
            setting.setAlwaysOpen(true);
            setting.touch();
            log.info("デモのため 24 時間受付に切り替えました（実店舗の営業時間は残っています）");
        }
    }

    /**
     * 在庫の見どころを作る。
     *
     * <p>「残り 3 個」「売り切れ」が画面に出ていないと、
     * 在庫管理を実装したことが録画から伝わりません。
     * 数字は少なめにして、注文するたびに減るところも撮れるようにします。
     */
    private void setUpStock() {
        List<MenuItem> items = menuItemRepository.findAll();

        // 残数を出す品。少なめの数字にして「あと少し」の緊張感を出す
        applyStock(items, "牡蠣と豚肉米粉そば", 3);
        applyStock(items, "海鮮スペシャル", 2);
        applyStock(items, "国産上ホルモン焼きそば", 5);

        // 売り切れの品を 1 つ。押せないボタンの見た目も見せどころ
        items.stream()
                .filter(i -> i.getName().contains("自家製レモンサワー"))
                .findFirst()
                .ifPresent(i -> {
                    i.setSoldOut(true);
                    menuItemRepository.save(i);
                });
    }

    /**
     * 「時価」の品を、動きを確かめられる状態にする。
     *
     * <p>時価の品（牛ステーキ 2 種・本日の日本酒・グラスワイン・ボトルワイン）は
     * {@code DataSeeder} が<b>全部 売り切れ</b>で登録します。
     * 価格 0 円のまま注文できると、伝票に ¥0 の行が残って金銭事故になるためです。
     * 実店舗の運用は「朝、当日の価格を入れて販売再開する」。
     *
     * <p>ところがそのままだと、店主が動きを確かめようとしても
     * <b>全部売り切れていて 1 つも試せません</b>。
     * そこでデモでは、3 つの状態を並べて置きます。
     * <ul>
     *   <li><b>当日価格を入れて販売再開</b>（国産牛サーロインステーキ ¥3,800）
     *       … 実店舗の正しい運用。ふつうの商品として注文できる</li>
     *   <li><b>0 円のまま販売再開</b>（本日の日本酒（おまかせ））
     *       … 価格を入れ忘れた状態。画面には「時価」と出て、注文もできてしまう。
     *       伝票に ¥0 で載るところまで、実際に見て確かめられる</li>
     *   <li><b>売り切れのまま</b>（グラスワイン・ボトルワイン・赤身ステーキ）
     *       … 出荷時の状態。時価の品が売り切れとして出る見た目</li>
     * </ul>
     *
     * <p>2 つ目をわざと残してあるのは、これが<b>いまのアプリの弱点</b>だからです。
     * 隠して見えなくするより、実物を見てから直すか決めるほうが早い。
     */
    private void openMarketPriceItems() {
        List<MenuItem> items = menuItemRepository.findAll();

        // 当日の価格を入れて販売再開（実店舗が毎朝やる操作そのもの）
        withItem(items, "国産牛サーロインステーキ", i -> {
            i.setPrice(3800);
            i.setSoldOut(false);
        });

        // 価格を入れないまま販売再開。「時価」と出たまま注文できる
        withItem(items, "本日の日本酒（おまかせ）", i -> i.setSoldOut(false));
    }

    private void withItem(List<MenuItem> items, String name, java.util.function.Consumer<MenuItem> change) {
        items.stream()
                .filter(i -> i.getName().equals(name))
                .findFirst()
                .ifPresent(i -> {
                    change.accept(i);
                    menuItemRepository.save(i);
                });
    }

    private void applyStock(List<MenuItem> items, String name, int remaining) {
        items.stream()
                .filter(i -> i.getName().equals(name))
                .findFirst()
                .ifPresent(i -> {
                    i.setStockRemaining(remaining);
                    menuItemRepository.save(i);
                });
    }

    /**
     * 見学用に、借りた写真を割り当てる。
     *
     * <hr>
     *
     * <h2>なぜ {@link DataSeeder} ではなくこちらに書くのか</h2>
     *
     * <p>ここで使う写真は<b>別の店（開発者の前職・親族の会社）の制作データ</b>を
     * 許可を得て借りたものです。米粉と鉄板の料理ではありません。
     *
     * <p>{@link DataSeeder} に書くと、<b>実店舗がいつか DB を作り直したときに
     * 他社の写真が本番のメニューに出ます。</b>
     * 店舗の商品写真として他店の料理が並ぶのは、単なる見栄えの問題ではなく、
     * 「頼んだものと違うものが来る」につながります。
     *
     * <p>このクラスは {@code @Profile({"dev","demo"})} なので、
     * <b>本番プロファイルではクラスごと読み込まれません。</b>
     * 借り物は借り物と分かる場所に置く、という切り分けです。
     * ファイルの置き場所も {@code images/menu/demo/} と分けてあります
     * （自前素材は {@code images/menu/} 直下）。
     *
     * <hr>
     *
     * <h2>料理名と写真が厳密には一致していません</h2>
     *
     * <p>借り元は<b>もんじゃ・鉄板焼きの店</b>で、品揃えが違います。
     * 「それらしいものを当てる」方針で、系統（麺・肉・サラダ・海鮮）を合わせています。
     * 名前の付け替えは後日おこなう前提です。
     *
     * <p><b>{@code gpt-} で始まるファイルは生成画像です。</b>
     * 借り元にも該当する料理が無く、実物の写真が手に入らなかった品に使っています。
     * 実写と生成が混ざるので、<b>ファイル名の先頭で区別が付く</b>ようにしてあります。
     * 実店舗で使う写真に紛れ込ませない、が理由です。
     * 本物の写真が撮れたら、この接頭辞のものから差し替えてください。
     *
     * <p><b>置いていない品もあります。</b>
     * 写真の無い品はプレースホルダのままです。
     * 画面は写真あり・なしが混在しても崩れない作りにしてあります。
     *
     * <hr>
     *
     * <h2>変換でつまずいたこと（CMYK の 5 チャンネル）</h2>
     *
     * <p>元データは印刷用の PSD です。最初は素直に読んで RGB へ変換したところ、
     * 一部が<b>虹色に破綻</b>し、瓶が二重に写るなど明らかにおかしくなりました。
     * CMYK を反転する定番の対処も試しましたが、真っ黒になるだけでした。
     *
     * <p>PSD のヘッダを直接読んで分かった原因がこれです。
     *
     * <pre>
     *   正常だったもの : ch=4  (C, M, Y, K)
     *   壊れたもの     : ch=5  (C, M, Y, K ＋ もう 1 つ)
     * </pre>
     *
     * <p>5 つ目はアルファか特色チャンネルです。
     * 汎用の画像ライブラリは 4 チャンネル前提で読むため、
     * <b>1 チャンネルぶん位置がずれ</b>、色が入れ替わって虹色になっていました。
     * ビット深度でもファイル破損でもなく、チャンネル数の想定違いでした。
     *
     * <p>PSD 専用のライブラリでレイヤーを合成してから書き出すと、全点が正しい RGB に
     * なりました。<b>「読めないファイル」ではなく「読み方が足りていない」だけだった</b>、
     * というのが結論です。
     *
     * <p>変換元の PSD はリポジトリに入れていません（他社の制作データで、
     * かつ 856MB あります）。作業用の複製は {@code data/psd-src/} に置いてあり、
     * ここは {@code .gitignore} の対象です。元フォルダは読み取りのみで触っていません。
     */
    /**
     * 商品名 → 写真ファイル名の対応表。
     *
     * <p><b>定数として外に出している理由</b><br>
     * {@link #photo} は名前が一致する商品を探して設定します。
     * 一致しなければ<b>何も起きません</b>。例外も警告も出ず、
     * ただ写真が付かないだけです。商品名を 1 文字直しただけで静かに壊れます。
     *
     * <p>ここに並べておけば、テストから同じ表を読んで
     * 「この名前の商品は実在するか」「このファイルは実在するか」を
     * 突き合わせられます。コードの中に埋めたままだと、それができません。
     */
    static final Map<String, String> DEMO_PHOTOS = new LinkedHashMap<>();

    static {
        // ── 広島風お好み焼き ──
        DEMO_PHOTOS.put("ねぎたっぷり米粉そば", "okonomi-negi.jpg");
        DEMO_PHOTOS.put("肉玉大葉げそ米粉そば", "hiroshima.jpg");
        DEMO_PHOTOS.put("牡蠣と豚肉米粉そば", "cheese-okonomi.jpg");
        DEMO_PHOTOS.put("海鮮スペシャル", "okonomi-kaisen.jpg");

        // ── 選べる米粉たこ焼き ──
        //   この店の看板メニュー。個数ごとに写真を変えている。
        //   同じ写真を使い回すと「個数で何が変わるのか」が伝わらない。
        //
        //   ★ 12/16/20 個は生成画像に差し替えた。
        //     実写のほうが質は高いが、写っている数が商品名と合っていなかった
        //     （「20個」の欄に 8 個の写真）。メニューで数が違うのは、
        //     見栄えの問題ではなく「頼んだものと違う」に直結する。
        //     4 個だけは実写のまま（写真もちょうど 4 個で一致している）。
        DEMO_PHOTOS.put("たこ焼 4個", "takoyaki-4.jpg");
        DEMO_PHOTOS.put("たこ焼 12個", "gpt-takoyaki-12.jpg");
        DEMO_PHOTOS.put("たこ焼 16個", "gpt-takoyaki-16.jpg");
        DEMO_PHOTOS.put("たこ焼 20個", "gpt-takoyaki-20.jpg");

        // ── 鉄板おつまみ ──
        DEMO_PHOTOS.put("殻付きホタテバター醤油（北海道産）", "gpt-hotate.jpg");
        DEMO_PHOTOS.put("国産鶏皮にんにく醤油焼", "gpt-torikawa.jpg");
        DEMO_PHOTOS.put("鉄板わかめ焼き（北海道産）", "wakame.jpg");
        DEMO_PHOTOS.put("鶏せせりとねぎの黒胡椒焼", "gpt-seseri.jpg");
        DEMO_PHOTOS.put("鉄板チョリソー五本", "chorizo.jpg");
        DEMO_PHOTOS.put("国産豚たん塩たれ焼", "gpt-butatan.jpg");
        DEMO_PHOTOS.put("国産砂肝の鉄板塩たれ焼", "gpt-sunagimo.jpg");
        DEMO_PHOTOS.put("きのことチーズの鉄板オープンオムレツ", "gpt-omelette.jpg");
        DEMO_PHOTOS.put("鉄板自家製ジャークチキン", "gpt-jerk-chicken.jpg");

        // ── 鉄板麺 ──
        DEMO_PHOTOS.put("米粉焼きうどん（出汁醤油）", "yakiudon.jpg");
        DEMO_PHOTOS.put("国産上ホルモン焼きそば", "yakisoba-hormone.jpg");

        // ── 数量限定鉄板焼き ──
        DEMO_PHOTOS.put("国産豚ロースステーキ", "pork-loin.jpg");
        DEMO_PHOTOS.put("国産牛サーロインステーキ", "beef-sirloin.jpg");
        DEMO_PHOTOS.put("国産牛赤身ステーキ", "beef-akami.jpg");
        DEMO_PHOTOS.put("鉄板たこ足塊ステーキ", "gpt-tako-steak.jpg");

        // ── 一品料理 ──
        DEMO_PHOTOS.put("蘭王ゆでねぎたま", "gpt-negitama.jpg");
        DEMO_PHOTOS.put("ピリ辛豆板醤きゅうり", "gpt-kyuri.jpg");
        DEMO_PHOTOS.put("さっぱりたこぽん", "gpt-takopon.jpg");
        DEMO_PHOTOS.put("本日の特製サラダ", "salad.jpg");

        // ── 甘味 ──
        DEMO_PHOTOS.put("本日のおすすめアイス", "dessert.jpg");

        // ── ビール・サワー ──
        DEMO_PHOTOS.put("サッポロ赤星（中瓶）", "beer-akaboshi.jpg");
        // モクテルのレモンソーダと同じ画。中身がほぼ同じなので揃える
        //（元は「レモンの絞り器」の写真で、飲み物そのものが写っていなかった）
        DEMO_PHOTOS.put("自家製レモンサワー", "gpt-mocktail.jpg");
        DEMO_PHOTOS.put("ジャスミンハイボール", "jasmine-highball.jpg");

        // ── ウィスキー ──
        //   角ハイボールだけは実物の商品写真がある。銘柄が一致するものは
        //   共通画像で上書きしない。あとは銘柄ごとのボトル写真を持っていないので、
        //   クラフトジンと同じ考えで「銘柄を写さない」一枚に揃える。
        DEMO_PHOTOS.put("角ハイボール", "kaku-highball.jpg");
        for (String name : List.of(
                "KIRIN 陸", "山崎", "白州", "響 Japanese Harmony", "知多",
                "宮城峡", "余市", "ハイランドパーク 12Years", "BOWMORE 12Years",
                "タリスカー 10Years", "Arran 10Years", "グレンモーレンジィ 12Years",
                "ニッカフロンティア")) {
            DEMO_PHOTOS.put(name, "gpt-whisky.jpg");
        }

        // ── 焼酎・スパイス ──
        //   お茶割り系は中身が写真から分かるので個別のまま。
        //   銘柄の焼酎だけ共通の一枚にする。
        DEMO_PHOTOS.put("黒ウーロンハイ", "kuro-oolong.jpg");
        DEMO_PHOTOS.put("お茶割り各種【ジャスミン割・緑茶割】", "green-tea.jpg");
        for (String name : List.of(
                "カルダモン焼酎", "AKAYANE 山椒", "佐藤【黒・白・麦】",
                "百年の孤独", "魔王")) {
            DEMO_PHOTOS.put(name, "gpt-shochu.jpg");
        }

        // ── ソフトドリンク ──
        //   レモンの炭酸なので、モクテルの写真がそのまま合う
        DEMO_PHOTOS.put("自家製レモンスカッシュ", "gpt-mocktail.jpg");
        DEMO_PHOTOS.put("自家製レモネード", "gpt-mocktail.jpg");
        DEMO_PHOTOS.put("コカ・コーラ", "cola.jpg");
        DEMO_PHOTOS.put("ジンジャーエール", "ginger-ale.jpg");
        DEMO_PHOTOS.put("黒烏龍茶", "kuro-oolong.jpg");
        DEMO_PHOTOS.put("緑茶", "matcha.jpg");
        DEMO_PHOTOS.put("ジャスミンティー", "green-tea.jpg");
        DEMO_PHOTOS.put("オレンジジュース", "orange-juice.jpg");
        //   どちらも無色の炭酸。グラスとボトルが1枚に写っているので両方に使える
        DEMO_PHOTOS.put("トニックウォーター", "gpt-sparkling.jpg");
        DEMO_PHOTOS.put("炭酸水", "gpt-sparkling.jpg");

        // ── モクテル（ノンアルカクテル）──
        //   3種とも炭酸系。中身が近いので共通の一枚で足りる。
        for (String name : List.of(
                "特製ノンアルレモンサワー", "ノンアルジントニック", "ノンアルシャンディガフ")) {
            DEMO_PHOTOS.put(name, "gpt-mocktail.jpg");
        }

        // ── 日本酒・ワイン ──
        //   「本日の日本酒（おまかせ）」は銘柄が日替わりなので、
        //   むしろ銘柄を写さない徳利の写真のほうが正しい。
        DEMO_PHOTOS.put("本日の日本酒（おまかせ）", "gpt-nihonshu.jpg");
        DEMO_PHOTOS.put("グラスワイン（赤・白）", "wine-red.jpg");
        DEMO_PHOTOS.put("ボトルワイン", "wine-white.jpg");
    }

    /** 見学用の写真を置く場所。自前素材（{@code /images/menu/}）と分けている。 */
    static final String DEMO_PHOTO_DIR = "/images/menu/demo/";

    private void setUpDemoPhotos() {
        List<MenuItem> items = menuItemRepository.findAll();
        DEMO_PHOTOS.forEach((name, file) -> items.stream()
                .filter(i -> i.getName().equals(name))
                .findFirst()
                .ifPresent(i -> {
                    i.setImagePath(DEMO_PHOTO_DIR + file);
                    menuItemRepository.save(i);
                }));
    }

    /** 埋めておきたい卓の数。全 10 卓のうち 6 卓（満席にはしない。空きも見せどころ）。 */
    private static final int TARGET_OCCUPIED = 6;

    /**
     * 注文の状態をこの順で回して配る。
     *
     * <p>厨房ボードは「受付 → 調理中 → 提供済」の 3 列で見せるので、
     * どの列にも品が並んでいる状態にします。
     * COMPLETED（提供済）も混ぜるのは、伝票側の見た目のためです。
     * 全品が未提供の伝票ばかりだと「座った直後の卓」しか無い店になり、
     * ホール画面の金額も育ちません。
     */
    private static final OrderStatus[] STATUS_CYCLE = {
            OrderStatus.COMPLETED, OrderStatus.RECEIVED, OrderStatus.COOKING,
            OrderStatus.RECEIVED, OrderStatus.READY, OrderStatus.COOKING,
            OrderStatus.RECEIVED, OrderStatus.COMPLETED,
    };

    /** たまに添える注文メモ。厨房ボードのメモ表示にも実物を出すため。 */
    private static final List<String> ORDER_NOTES = List.of(
            "ソース多めで", "マヨネーズ抜きでお願いします", "取り皿を2枚ください");

    /**
     * 前の営業日から残っている OPEN の伝票を片付ける。
     *
     * <p>提供まで済ませてから会計します。未提供のまま締めると、
     * その注文は受付から 6 時間（{@code OrderService.CARRY_OVER_WINDOW}）は
     * 厨房ボードに「焼き忘れ」として残り続け、せっかく作る今日の景色に
     * 昨日のゴミが混ざるからです。
     *
     * <p>今の営業日の伝票には触りません。自分で開いて操作中の伝票を
     * 勝手に会計されたら、それはデータ投入ではなく妨害です。
     */
    private int closeLeftoverSessions() {
        var today = shopSettingService.currentBusinessDate();
        int cleaned = 0;
        for (TableSession session : tableService.openSessions()) {
            if (today.equals(session.getBusinessDate())) {
                continue;
            }
            for (Order order : session.getOrders()) {
                completeOrder(order);
            }
            tableService.closeSession(session.getId(), true, "デモ", "前の営業日の片付け");
            cleaned++;
        }
        return cleaned;
    }

    /**
     * 卓の埋まりが目標（{@value #TARGET_OCCUPIED} 卓）になるまで伝票と注文を足す。
     *
     * <p>撮影用の卓と、すでに伝票が開いている卓は飛ばします。
     * 目標に達していれば 1 卓も作らないので、同じ日に何度起動しても増えません。
     */
    private int fillLiveScene() {
        List<MenuItem> candidates = orderCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }

        long occupied = tableService.openSessions().size();
        int created = 0;
        int dish = 0;
        int waited = 0;

        for (DiningTable table : tableRepository.findAll()) {
            if (occupied + created >= TARGET_OCCUPIED) {
                break;
            }
            if (STAGE_TABLE.equals(table.getName())
                    || tableService.currentSession(table.getId()).isPresent()) {
                continue;
            }

            // カウンターは 1〜2 名、テーブルは 2〜4 名。席の種類で人数の相場が違う
            int guests = table.getCapacity() <= 1
                    ? 1 + (created % 2)
                    : 2 + (created % 3);
            TableSession session = tableService.openSession(table.getId(), guests);

            // 1 卓 1〜3 回の注文。「とりあえずビール→料理→追加」の追い注文を模す
            int orders = 1 + (created % 3);
            for (int j = 0; j < orders; j++) {
                Order order = order(session, candidates, dish, 1 + ((created + j) % 2));
                if (order != null) {
                    advanceTo(order, STATUS_CYCLE[dish % STATUS_CYCLE.length]);
                    backdate(order, WAITED_MINUTES[waited % WAITED_MINUTES.length]);
                }
                dish += 2;
                waited++;
            }
            created++;
        }
        return created;
    }

    /**
     * 注文を「何分前に受け付けたことにするか」。
     *
     * <p><b>15 分未満に収めているのには理由があります。</b>
     * 厨房ボードは見学モード（{@code app.guest-login=true}）のとき、
     * 15 分を超えた注文の経過時間を<b>数字ごと消します</b>
     * （{@code KitchenController.DEMO_STALE_MINUTES}。起動時に置いた注文が
     * 何時間も居座って画面が真っ赤になるのを防ぐため）。
     * 「27 分」「63 分」と散らしたところ、ボードの経過時間欄が
     * ほとんど空欄になり、かえって判断できない画面になりました。
     *
     * <p>そのうえで<b>値をばらけさせる</b>のが目的です。
     * 全部が同じ分数だと、並び順にも色にも意味が見えません。
     * 要素数を 7 にしてあるのは、状態の周期（{@link #STATUS_CYCLE} は 8 個）と
     * 割り切れないようにするためです。同じ長さにすると
     * 「調理中はいつも 11 分」のように状態と分数が固定で結び付きます。
     */
    private static final int[] WAITED_MINUTES = {2, 9, 5, 13, 1, 11, 7};

    /**
     * 注文の受付時刻を巻き戻す。
     *
     * <p>JPQL の更新文で DB を直接書き換えます。理由は {@link #entityManager} の説明のとおりです。
     *
     * <p><b>前後の {@code flush} と {@code refresh} は省けません。</b>
     * 更新文は<b>永続化コンテキストを迂回して</b> DB へ直接飛びます。
     * <ul>
     *   <li>先に {@code flush} しないと、まだ DB に出ていない注文を更新することになり
     *       0 件更新で静かに空振りする</li>
     *   <li>あとで {@code refresh} しないと、メモリ上の写しは古い時刻のままなので、
     *       コミット時の変更検知が<b>巻き戻した時刻を元に戻してしまう</b></li>
     * </ul>
     * {@code clear()} で全部捨てないのは、まだ書き出していない他の伝票の変更まで
     * 道連れになるからです。直した 1 件だけ読み直します。
     */
    private void backdate(Order order, int minutes) {
        entityManager.flush();
        entityManager.createQuery(
                        "update Order o set o.createdAt = :at where o.id = :id")
                .setParameter("at", order.getCreatedAt().minusMinutes(minutes))
                .setParameter("id", order.getId())
                .executeUpdate();
        entityManager.refresh(order);
    }

    /**
     * 今の営業日に会計済みの伝票が 1 つも無ければ、先に帰った組を作る。
     *
     * <p>ダッシュボードと売上画面の「本日」は<b>会計済みの伝票</b>から数えます。
     * 営業中の伝票をいくら積んでも本日の売上は 0 円のままなので、
     * 最初から最後まで済ませた組が別に要ります。
     * 会計まで済ませるので卓はまた空きに戻り、卓を消費しません。
     */
    private int closeEarlierGuests() {
        var today = shopSettingService.currentBusinessDate();
        boolean alreadyClosed = tableService.sessionsOf(today).stream()
                .anyMatch(s -> !s.isOpen());
        if (alreadyClosed) {
            return 0;
        }

        List<MenuItem> candidates = orderCandidates();
        if (candidates.isEmpty()) {
            return 0;
        }

        int closed = 0;
        int dish = 1;
        for (DiningTable table : tableRepository.findAll()) {
            if (closed >= 2) {
                break;
            }
            if (STAGE_TABLE.equals(table.getName())
                    || tableService.currentSession(table.getId()).isPresent()) {
                continue;
            }
            TableSession session = tableService.openSession(table.getId(), 2 + closed);
            for (int j = 0; j < 3; j++) {
                Order order = order(session, candidates, dish, 1 + (j % 2));
                if (order != null) {
                    completeOrder(order);
                }
                dish += 3;
            }
            tableService.closeSession(session.getId(), true, "デモ", null);
            closed++;
        }
        return closed;
    }

    /**
     * 伝票に注文を 1 件入れる。
     *
     * <p>本番と同じ道（カートに入れて注文する）を通します。
     * ここだけ直接 INSERT すると、金額の計算や在庫の引き当てを通らず、
     * 「録画では合っていたのに実際は違う」という一番まずいデモになります。
     */
    private Order order(TableSession session, List<MenuItem> candidates, int seed, int dishes) {
        Cart cart = new Cart();
        for (int i = 0; i < dishes; i++) {
            MenuItem item = candidates.get((seed + i * 7) % candidates.size());
            cartService.addToCart(cart, item.getId(), List.of(), 1 + ((seed + i) % 2));
        }
        // 3 件に 1 件だけメモを付ける。全件に付くと逆に作り物くさい
        String note = (seed % 3 == 0)
                ? ORDER_NOTES.get((seed / 3) % ORDER_NOTES.size())
                : null;
        return orderService.placeOrder(cart, session.getId(), note);
    }

    /** デモの注文に使える品を選ぶ。 */
    private List<MenuItem> orderCandidates() {
        return menuItemRepository.findAll().stream()
                .filter(MenuItem::isVisible)
                .filter(i -> !i.isSoldOut())
                // オプション必須の品はカートに入れる条件が複雑なので、ここでは避ける
                .filter(i -> i.getOptionGroups().isEmpty())
                // 「残り○個」を見せるために絞った品は避ける。
                // デモの注文が食いつぶすと、見せたい残数がゼロになってしまう
                .filter(i -> i.getStockRemaining() == null)
                .toList();
    }

    /** 受付から目的の状態まで、正規の順（調理中 → 提供準備 → 提供済）で進める。 */
    private void advanceTo(Order order, OrderStatus target) {
        if (target == OrderStatus.RECEIVED) {
            return;
        }
        orderService.changeStatus(order.getId(), OrderStatus.COOKING, "厨房スタッフ");
        if (target == OrderStatus.COOKING) {
            return;
        }
        orderService.changeStatus(order.getId(), OrderStatus.READY, "厨房スタッフ");
        if (target == OrderStatus.READY) {
            return;
        }
        orderService.changeStatus(order.getId(), OrderStatus.COMPLETED, "ホールスタッフ");
    }

    /** 未提供の注文を提供済みまで進める（すでに済んでいれば何もしない）。 */
    private void completeOrder(Order order) {
        switch (order.getStatus()) {
            case RECEIVED -> advanceTo(order, OrderStatus.COMPLETED);
            case COOKING -> {
                orderService.changeStatus(order.getId(), OrderStatus.READY, "厨房スタッフ");
                orderService.changeStatus(order.getId(), OrderStatus.COMPLETED, "ホールスタッフ");
            }
            case READY -> orderService.changeStatus(order.getId(), OrderStatus.COMPLETED, "ホールスタッフ");
            default -> { /* 提供済み・キャンセルはそのまま */ }
        }
    }

    /** 撮影用の卓を探す（見つからなければ空）。 */
    Optional<DiningTable> stageTable() {
        return tableRepository.findAll().stream()
                .filter(t -> STAGE_TABLE.equals(t.getName()))
                .findFirst();
    }
}
