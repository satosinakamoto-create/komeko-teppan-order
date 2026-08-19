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

import java.util.List;
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
 */
@Component
@Profile({"dev", "demo"})
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
