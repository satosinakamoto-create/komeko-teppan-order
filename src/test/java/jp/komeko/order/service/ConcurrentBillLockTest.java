package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2 つの操作が<b>ほぼ同時に</b>同じ伝票へ届いたときの回帰テスト。
 *
 * <p><b>このテストが守っているもの＝「会計が黙って消えない」こと。</b><br>
 * このアプリのエンティティには {@code @Version}（楽観ロック）が無く、
 * {@code @DynamicUpdate} も付けていないため、Hibernate は更新のたびに
 * <b>そのエンティティの全カラム</b>を {@code where id = ?} だけで UPDATE します。
 * つまり「古い内容を抱えたスレッドが後からコミットすると、
 * 自分が触っていない列まで巻き戻る」という壊れ方をします。
 *
 * <pre>
 *   B: 伝票を読む（status = OPEN）
 *   A: お会計を完走してコミット（status = CLOSED / closedBy / 合計 ¥2,900）
 *   B: if (!session.isOpen()) を<b>古い写し</b>で通過し、そのままコミット
 *      → status=OPEN closedAt=null closedBy=null …… お会計が例外も出さずに消える
 * </pre>
 *
 * <p>直し方は「書き換える経路は、<b>読む前に</b>伝票の行ロックを取る」だけです。
 * 後から来たほうは先の操作がコミットするまで待たされ、待ったあとに
 * <b>DB の最新の状態を読み直す</b>ので、自分のチェックが正しく効きます。
 *
 * <p><b>テストの作り方（ここが肝）</b>
 * <ul>
 *   <li>{@code @Transactional} を<b>付けない</b>。付けるとテスト全体が
 *       1 つの永続化コンテキストを共有してしまい、まさにこの種のバグが消える
 *       （{@code KitchenCancelHttpTest} と同じ理由）。本番と同じ「1 操作 1 トランザクション」で走らせる。</li>
 *   <li>スレッド A はテストが握るトランザクションの中で操作し、
 *       <b>コミットせずに</b>相手がロック待ちに入るのを待つ。
 *       これで「B が読んだあとに A がコミットする」という交差を毎回同じ順序で作れる。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("同じ伝票への同時操作（伝票の行ロック）")
class ConcurrentBillLockTest {

    /** 相手のスレッドがロック待ちに入ったと判断するまでに必要な観測回数。 */
    private static final int BLOCKED_SAMPLES_REQUIRED = 5;

    /** 観測の間隔（ミリ秒）。5 回 × 20ms ＝ 最低 100ms は「止まったまま」を確かめる。 */
    private static final long SAMPLE_INTERVAL_MILLIS = 20L;

    /** 相手が止まるのを待つ上限。ここまで待って動きっぱなしなら諦めて先へ進む。 */
    private static final int MAX_SAMPLES = 150;

    @Autowired
    private TableService tableService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private CartService cartService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository sessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate ownTransaction;
    private MenuItem item;
    private Long billId;
    private Long orderId;
    private String orderToken;

    @BeforeEach
    void setUp() {
        clearAll();
        ownTransaction = new TransactionTemplate(transactionManager);

        // いつ走らせても同じ金額になるように受付条件と料金を固定する
        // （深夜料金が乗ると実行時刻で金額が変わり、検証がぶれるため 0%）
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setTaxRatePercent(10);
        setting.setTableChargePerGuest(450);
        setting.setLateNightSurchargePercent(0);
        setting.setOrderNumberStart(101);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem newItem = new MenuItem(category, "肉玉米粉そば", 1000);
        newItem.setCookMinutes(12);
        newItem.setStockRemaining(5);   // 残数を管理する品にして、在庫の戻し過ぎまで見る
        item = menuItemRepository.save(newItem);

        DiningTable table = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10));
        TableSession bill = tableService.openSession(table.getId(), 2);
        billId = bill.getId();

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 2);
        Order placed = orderService.placeOrder(cart, billId, null);
        orderId = placed.getId();
        orderToken = placed.getPublicToken();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    /** 参照している側から先に消す（外部キー制約）。 */
    private void clearAll() {
        orderRepository.deleteAll();
        sessionRepository.deleteAll();
        diningTableRepository.deleteAll();
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
        dailyCounterRepository.deleteAllInBatch();
    }

    // ========================================================================
    //  会計 × 伝票の書き換え
    // ========================================================================

    @Test
    @DisplayName("お会計の締めと人数変更が交差しても、締めた会計が消えない")
    void closingIsNotUndoneByConcurrentGuestCountChange() {
        // 守るもの: 会計は売上が確定する操作。ここが黙って OPEN に戻ると、
        // レジには現金があるのに伝票は「未会計」、という取り返しのつかない食い違いになる。
        // 人数変更（ホール画面）は会計の直前によく触るので、交差は現実に起こる。
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runAgainstUncommittedClose(() -> tableService.changeGuestCount(billId, 1), failure);

        TableSession stored = sessionRepository.findById(billId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(stored.getClosedAt()).isNotNull();
        assertThat(stored.getClosedBy()).isEqualTo("店長");
        // 1,000 円 × 2 点 ＋ テーブルチャージ 450 円 × 2 名
        assertThat(stored.getTotalAmount()).isEqualTo(2900);
        assertThat(stored.getGuestCount()).isEqualTo(2);
        // 後から来たほうは、待たされたあとに CLOSED を読み直して自分から断るのが正しい姿
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("お会計の締めとお客さまのキャンセルが交差しても、締めた会計が消えない")
    void closingIsNotUndoneByConcurrentCustomerCancel() {
        // 守るもの: 「お会計しました」の直後に、手元のスマホから取り消しを押される、
        // という順番は毎晩ありうる。ここを通すと会計が OPEN に戻るうえ、
        // 請求済みの品が小計から外れ、在庫まで増える（＝売り物が幻で増える）。
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runAgainstUncommittedClose(() -> orderService.cancelByCustomer(orderToken), failure);

        TableSession stored = sessionRepository.findById(billId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(stored.getSubtotalAmount()).isEqualTo(2000);
        assertThat(stored.getTotalAmount()).isEqualTo(2900);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.RECEIVED);
        assertThat(stockRemaining()).isEqualTo(3);   // 5 − 2。戻していない
        assertThat(failure.get()).isInstanceOf(OrderRejectedException.class);
    }

    // ========================================================================
    //  同じ注文への同時キャンセル（M6）／キャンセルと調理開始の交錯（M7）
    // ========================================================================

    @Test
    @DisplayName("厨房とお客さまが同時にキャンセルしても、在庫は 1 回しか戻らない")
    void concurrentCancelRestoresStockOnlyOnce() {
        // 守るもの: 残数（在庫）。キャンセルのたびに +数量 するので、
        // 二重に走ると「無い在庫」が画面に現れ、そのぶん売り越す。
        // 厨房が「材料切れ」で取り消したのと同時にお客さまも取り消しを押す、
        // という交差は、まさに材料切れを伝えた直後に起こる。
        assertThat(stockRemaining()).isEqualTo(3);   // 2 点注文したので 5 → 3

        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runAgainstUncommitted(
                () -> orderService.cancelByStaff(orderId, "材料切れ", "店長"),
                () -> orderService.cancelByCustomer(orderToken),
                failure);

        assertThat(stockRemaining()).isEqualTo(5);   // 3 + 2。+2 が二重に走って 7 になってはいけない
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(sessionRepository.findById(billId).orElseThrow().getSubtotalAmount()).isZero();
        assertThat(failure.get()).isInstanceOf(OrderRejectedException.class);
    }

    @Test
    @DisplayName("キャンセルと「調理開始」が交錯しても、キャンセルが取り消されない")
    void cookingDoesNotResurrectACanceledOrder() {
        // 守るもの: 取り消した注文が請求に戻らないこと。
        // お客さまが取り消したのと同時に厨房が「調理開始」を押すと、厨房側の古い写しが
        // 注文を COOKING に書き戻し、キャンセル日時も理由も消える。
        // お客さまの画面には「キャンセル済み」と出たまま、伝票には金額が戻り、
        // さらに在庫だけは +数量 された状態が残る（誰も気づけない）。
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runAgainstUncommitted(
                () -> orderService.cancelByCustomer(orderToken),
                () -> orderService.changeStatus(orderId, OrderStatus.COOKING, "厨房"),
                failure);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(sessionRepository.findById(billId).orElseThrow().getSubtotalAmount()).isZero();
        assertThat(stockRemaining()).isEqualTo(5);   // 3 + 2。キャンセル 1 回ぶんだけ戻る
        // CANCELED から COOKING への遷移は許されていないので、後発は自分で気づいて断る
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    // ========================================================================
    //  交差を作るための道具
    // ========================================================================

    /** 「お会計を締めたがまだコミットしていない」状態にぶつける。 */
    private void runAgainstUncommittedClose(Runnable second, AtomicReference<RuntimeException> failure) {
        runAgainstUncommitted(
                () -> tableService.closeSession(billId, false, "店長", null),
                second,
                failure);
    }

    /**
     * {@code first} を<b>コミットせずに</b>止めておき、その隙に {@code second} を走らせる。
     *
     * <p>手順は次のとおり。本番の「1 操作 1 トランザクション」を保ったまま、
     * 交差の順序だけをテストが決められるようにしています。
     *
     * <ol>
     *   <li>後発（{@code second}）を別スレッドで起動し、待たせておく</li>
     *   <li>先発（{@code first}）をテストが握るトランザクションで実行し、
     *       {@code flush()} で UPDATE を DB に送る（＝行ロックを確実に握る）。
     *       本番ではコミット時にまとめて送られるが、テストでは
     *       「A が握っている」状態を先に作らないと交差が安定しない</li>
     *   <li>後発を起こし、<b>DB のロック待ちに入るまで</b>待つ</li>
     *   <li>先発をコミットする（ここで後発が動き出す）</li>
     * </ol>
     *
     * <p>後発が投げた例外は握りつぶさず {@code failure} に入れて返します。
     * 「後発が自分で気づいて断った」ことまで確かめたいためです。
     */
    private void runAgainstUncommitted(Runnable first, Runnable second,
                                       AtomicReference<RuntimeException> failure) {
        CountDownLatch firstIsPending = new CountDownLatch(1);
        CountDownLatch secondHasStarted = new CountDownLatch(1);

        Thread later = new Thread(() -> {
            try {
                if (!firstIsPending.await(10, TimeUnit.SECONDS)) {
                    return;
                }
                secondHasStarted.countDown();
                second.run();
            } catch (RuntimeException e) {
                failure.set(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "後発の操作");
        later.start();

        ownTransaction.executeWithoutResult(status -> {
            first.run();
            // コミット前に UPDATE を DB へ送り、行ロックを握った状態を作る
            sessionRepository.flush();
            firstIsPending.countDown();
            awaitStarted(secondHasStarted);
            awaitBlockedOnDatabase(later);
        });

        joinQuietly(later);
    }

    /**
     * 相手のスレッドが「DB のロック待ちで止まった」と言える状態になるまで待つ。
     *
     * <p>{@code Thread.sleep(300)} のような決め打ちにしないのは、
     * 遅いマシンで相手がまだ読み取りにも到達していないうちにコミットしてしまうと、
     * <b>バグがあるのにテストが通ってしまう</b>（見せかけの green になる）ためです。
     * 逆に速いマシンでは無駄に待ちません。
     *
     * <p>ロック待ちのスレッドは実行中（RUNNABLE）ではなくなるので、
     * 「実行中でない」状態が続けて何回か観測できたら止まったとみなします。
     * 1 回だけの観測で判断しないのは、たまたま別の理由で一瞬止まっただけ、
     * を拾わないためです。
     */
    private static void awaitBlockedOnDatabase(Thread other) {
        int blockedSamples = 0;
        for (int i = 0; i < MAX_SAMPLES && other.isAlive(); i++) {
            Thread.State state = other.getState();
            if (state == Thread.State.BLOCKED
                    || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                if (++blockedSamples >= BLOCKED_SAMPLES_REQUIRED) {
                    return;
                }
            }
            sleepQuietly(SAMPLE_INTERVAL_MILLIS);
        }
    }

    private static void awaitStarted(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Integer stockRemaining() {
        return menuItemRepository.findById(item.getId()).orElseThrow().getStockRemaining();
    }
}
