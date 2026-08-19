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
 * <p>また、すでに開いている伝票があるときは<b>何もせずに終わります</b>。
 * 撮影のたびに走らせても伝票が積み上がらず、
 * 営業中にうっかり実行しても実データを荒らしません。
 *
 * <p><b>⚠ 撮影する日に実行すること</b><br>
 * 厨房ボードは<b>その日の営業日ぶん</b>しか表示しません。
 * 前の日に作ったデモデータは、日付が変わると（正確には
 * 営業日の切り替え時刻 5:00 をまたぐと）ボードから消えます。
 * 実際、深夜 3:39 に作ったデータが翌朝 11:39 には 1 件も出ませんでした。
 * バグではなく、営業日で区切る仕様どおりの動きです。
 * <b>撮る直前に走らせてください。</b>
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

    /** true のときだけデモデータを入れる。既定は false（うっかり動かないように）。 */
    private final boolean enabled;

    public DemoDataSeeder(DiningTableRepository tableRepository,
                          MenuItemRepository menuItemRepository,
                          TableService tableService,
                          CartService cartService,
                          OrderService orderService,
                          ShopSettingService shopSettingService,
                          @Value("${app.demo-data:false}") boolean enabled) {
        this.tableRepository = tableRepository;
        this.menuItemRepository = menuItemRepository;
        this.tableService = tableService;
        this.cartService = cartService;
        this.orderService = orderService;
        this.shopSettingService = shopSettingService;
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
        if (!tableService.openSessions().isEmpty()) {
            log.info("開いている伝票があるため、デモデータの投入を見送りました。"
                    + "ホール画面（/hall）で全部お会計してから、もう一度 -Demo で起動してください");
            return;
        }

        openTheShop();
        setUpStock();
        setUpDemoPhotos();
        int created = fillOtherTables();

        log.warn("""

                ============================================================
                 撮影用のデモデータを入れました。
                   ・{} 卓ぶんの伝票を作成（厨房ボードが埋まります）
                   ・在庫の残数と品切れを設定（残り○個 / 売り切れが映ります）
                   ・{} は空けてあります ← ここで QR を読んで撮影してください

                 撮り終わったら ホール画面から会計して片付けてください。
                ============================================================
                """, created, STAGE_TABLE);
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
        //   この店の看板メニュー。撮影データがそろっているので個数ごとに変えている。
        //   同じ写真を使い回すと「個数で何が変わるのか」が伝わらない。
        DEMO_PHOTOS.put("たこ焼 4個", "takoyaki-4.jpg");
        DEMO_PHOTOS.put("たこ焼 12個", "takoyaki-12.jpg");
        DEMO_PHOTOS.put("たこ焼 16個", "takoyaki-16.jpg");
        DEMO_PHOTOS.put("たこ焼 20個", "takoyaki-20.jpg");

        // ── 鉄板おつまみ ──
        DEMO_PHOTOS.put("殻付きホタテバター醤油（北海道産）", "hotate.jpg");
        DEMO_PHOTOS.put("国産鶏皮にんにく醤油焼", "torikawa.jpg");
        DEMO_PHOTOS.put("鉄板わかめ焼き（北海道産）", "wakame.jpg");
        DEMO_PHOTOS.put("鶏せせりとねぎの黒胡椒焼", "gpt-seseri.jpg");
        DEMO_PHOTOS.put("鉄板チョリソー五本", "chorizo.jpg");
        DEMO_PHOTOS.put("国産豚たん塩たれ焼", "gpt-butatan.jpg");
        DEMO_PHOTOS.put("国産砂肝の鉄板塩たれ焼", "gpt-sunagimo.jpg");
        DEMO_PHOTOS.put("きのことチーズの鉄板オープンオムレツ", "gpt-omelette.jpg");
        DEMO_PHOTOS.put("鉄板自家製ジャークチキン", "jerk-chicken.jpg");

        // ── 鉄板麺 ──
        DEMO_PHOTOS.put("米粉焼きうどん（出汁醤油）", "yakiudon.jpg");
        DEMO_PHOTOS.put("国産上ホルモン焼きそば", "yakisoba-hormone.jpg");

        // ── 数量限定鉄板焼き ──
        DEMO_PHOTOS.put("国産豚ロースステーキ", "pork-loin.jpg");
        DEMO_PHOTOS.put("国産牛サーロインステーキ", "beef-sirloin.jpg");
        DEMO_PHOTOS.put("国産牛赤身ステーキ", "beef-akami.jpg");
        DEMO_PHOTOS.put("鉄板たこ足塊ステーキ", "gpt-tako-steak.jpg");

        // ── 一品料理 ──
        DEMO_PHOTOS.put("たこのねぎまみれ", "tako-negi.jpg");
        DEMO_PHOTOS.put("冷やしトマト", "tomato.jpg");
        DEMO_PHOTOS.put("蘭王ゆでねぎたま", "soup.jpg");
        DEMO_PHOTOS.put("自家製ポテサラ", "potato.jpg");
        DEMO_PHOTOS.put("ピリ辛豆板醤きゅうり", "kyuri.jpg");
        DEMO_PHOTOS.put("さっぱりたこぽん", "takopon.jpg");
        DEMO_PHOTOS.put("本日の特製サラダ", "salad.jpg");

        // ── 甘味 ──
        DEMO_PHOTOS.put("本日のおすすめアイス", "dessert.jpg");

        // ── ビール・サワー ──
        DEMO_PHOTOS.put("サッポロ赤星（中瓶）", "beer-akaboshi.jpg");
        DEMO_PHOTOS.put("オールフリー（ノンアルコールビール）", "nonal-beer.jpg");
        DEMO_PHOTOS.put("自家製レモンサワー", "lemon-sour.jpg");
        DEMO_PHOTOS.put("ジャスミンハイボール", "jasmine-highball.jpg");

        // ── ウィスキー ──
        DEMO_PHOTOS.put("角ハイボール", "kaku-highball.jpg");

        // ── 焼酎・スパイス ──
        DEMO_PHOTOS.put("黒ウーロンハイ", "kuro-oolong.jpg");
        DEMO_PHOTOS.put("お茶割り各種【ジャスミン割・緑茶割】", "green-tea.jpg");

        // ── ソフトドリンク ──
        DEMO_PHOTOS.put("コカ・コーラ", "cola.jpg");
        DEMO_PHOTOS.put("ジンジャーエール", "ginger-ale.jpg");
        DEMO_PHOTOS.put("黒烏龍茶", "kuro-oolong.jpg");
        DEMO_PHOTOS.put("緑茶", "matcha.jpg");
        DEMO_PHOTOS.put("ジャスミンティー", "green-tea.jpg");
        DEMO_PHOTOS.put("オレンジジュース", "orange-juice.jpg");

        // ── 日本酒・ワイン ──
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

    /**
     * 撮影用の卓以外に、それらしい伝票を作る。
     *
     * <p>厨房ボードは「受付 → 調理中 → 提供済」の 3 列で見せるので、
     * どの列にも品が並んでいる状態にします。
     * 1 列だけ埋まっていても、ボードの意味が伝わりません。
     */
    private int fillOtherTables() {
        List<DiningTable> tables = tableRepository.findAll().stream()
                .filter(t -> !STAGE_TABLE.equals(t.getName()))
                .limit(3)
                .toList();

        int created = 0;
        for (int i = 0; i < tables.size(); i++) {
            DiningTable table = tables.get(i);
            TableSession session = tableService.openSession(table.getId(), 2 + i);

            // 卓ごとに違う品を頼ませる。同じ品ばかりだと「1件を複製しただけ」に見える
            Order first = order(session, i * 2);
            Order second = order(session, i * 2 + 1);

            // 状態をばらけさせて、ボードの3列すべてに品を置く
            if (first != null && i >= 1) {
                orderService.changeStatus(first.getId(), OrderStatus.COOKING, "厨房スタッフ");
            }
            if (second != null && i >= 2) {
                orderService.changeStatus(second.getId(), OrderStatus.COOKING, "厨房スタッフ");
                orderService.changeStatus(second.getId(), OrderStatus.READY, "厨房スタッフ");
            }
            created++;
        }
        return created;
    }

    /**
     * 伝票に注文を 1 件入れる。
     *
     * <p>本番と同じ道（カートに入れて注文する）を通します。
     * ここだけ直接 INSERT すると、金額の計算や在庫の引き当てを通らず、
     * 「録画では合っていたのに実際は違う」という一番まずいデモになります。
     */
    private Order order(TableSession session, int seed) {
        List<MenuItem> candidates = menuItemRepository.findAll().stream()
                .filter(MenuItem::isVisible)
                .filter(i -> !i.isSoldOut())
                // オプション必須の品はカートに入れる条件が複雑なので、ここでは避ける
                .filter(i -> i.getOptionGroups().isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        MenuItem item = candidates.get(seed % candidates.size());

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 1 + (seed % 2));
        return orderService.placeOrder(cart, session.getId(), null);
    }

    /** 撮影用の卓を探す（見つからなければ空）。 */
    Optional<DiningTable> stageTable() {
        return tableRepository.findAll().stream()
                .filter(t -> STAGE_TABLE.equals(t.getName()))
                .findFirst();
    }
}
