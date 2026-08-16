package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OrderService} の結合テスト（DB を本当に使う）。
 *
 * <p><b>このテストが守っているもの＝「注文が、正しい卓の伝票に 1 件だけ入ること」</b><br>
 * イートインでは、注文は必ずどこかの<b>伝票（{@link TableSession}）</b>にぶら下がります。
 * 伝票に入らない注文は誰にも請求されず、
 * 違う卓の伝票に入れば、まったく関係のないお客さんに請求されます。
 * 注文の受付は、カート・店舗設定・採番・保存・伝票への追加・通知という
 * 複数の部品が噛み合って初めて成立します。
 * 部品ごとの単体テストが全部通っていても、つなぎ目がずれていれば注文は通りません。
 * そこを見るのが結合テストです。
 *
 * <p><b>付けているアノテーションの意味</b>
 * <ul>
 *   <li>{@code @SpringBootTest} … アプリ本体と同じように Spring を丸ごと起動する。
 *       DI も DB もトランザクションも本番と同じ仕組みで動く。</li>
 *   <li>{@code @ActiveProfiles("test")} … application-test.yml を読ませ、
 *       開発用のファイル DB ではなくメモリ上の H2 を使わせる。</li>
 *   <li>{@code @Transactional} … <b>各テストメソッドが終わったら DB の変更を自動で巻き戻す</b>。
 *       テストの実行順に関係なく、毎回まっさらな状態から始められる。</li>
 * </ul>
 *
 * <p><b>なぜ Cart を DI せず {@code new Cart()} するのか</b><br>
 * {@link Cart} は {@code @SessionScope} の Bean です。
 * これは「ブラウザのセッションごとに 1 個」という意味で、
 * 実体を取り出すには HTTP リクエストの文脈（どのセッションか）が必要になります。
 * テストには HTTP リクエストが無いので、DI しようとすると
 * {@code No thread-bound request found} というエラーになります。
 * さらに、DI してしまうとテスト間で同じカートを共有してしまい、
 * 前のテストの中身が残る危険もあります。
 * {@code OrderService#placeOrder} はカートを<b>引数で</b>受け取る設計なので、
 * ここでは素直に {@code new Cart()} して渡すのが正解です。
 * （「必要なものは引数で渡す」設計にしておくとテストが一気にラクになる好例）
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("注文サービス（DBあり）")
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private TableService tableService;
    @Autowired
    private CartService cartService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ShopSetting setting;
    private DiningTable table;
    /**
     * テストで使う伝票。
     *
     * <p>変数名を {@code session} にしていないのは、このプロジェクト全体で
     * 「伝票は bill と呼ぶ」と決めているからです
     * （Thymeleaf では {@code ${session}} が HTTP セッションの予約名なので、
     * 画面側で使えません。呼び方は上から下までそろえておくと混乱しません）。
     */
    private TableSession bill;
    private MenuItem okonomiyaki;
    private MenuItem sour;

    @BeforeEach
    void setUp() {
        // ── 採番カウンタだけは「別トランザクションで」消す ─────────────
        // OrderNumberService#next は Propagation.REQUIRES_NEW が付いており、
        // 呼び出し元とは別のトランザクションで採番してすぐコミットします。
        // つまり @Transactional によるロールバックの対象外で、
        // 前のテストが払い出した番号が DB に残り続けます。
        // そこで、こちらも REQUIRES_NEW（＝いまのテスト用トランザクションを一旦中断して
        // 独立したトランザクションを開く）で消してから始めます。
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.executeWithoutResult(status -> dailyCounterRepository.deleteAllInBatch());

        // ── 店舗設定を「いつテストを走らせても同じ結果になる」値にそろえる ──
        // 実店舗の設定は 17:30 開店・翌 1:30 ラストオーダーなので、
        // そのままだと昼に流したときだけ「営業時間外」で落ちてしまう。
        setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);                 // 00:00
        // LocalTime.MAX（23:59:59.999999999）は DB の TIME 型が秒までしか持てず
        // 丸めが起きることがあるので、秒までの値にしておく。
        setting.setLastOrderTime(LocalTime.of(23, 59, 59)); // 実質いつでも受付可
        setting.setBusinessDayCutoverHour(0);               // 営業日＝暦の日付 にそろえる
        setting.setOrderNumberStart(101);
        setting.setTaxRatePercent(10);                      // 酒類を扱う店なので軽減税率の対象外
        setting.setGriddleCapacity(4);
        setting.setTableChargePerGuest(450);
        // 深夜料金は「実行した時刻」で自動判定されるため、金額の検証がぶれる。
        // このクラスは注文まわりの検証が目的なので 0% にして影響を消しておく。
        setting.setLateNightSurchargePercent(0);

        // ── 卓と伝票を用意する ──────────────────────────────────
        // イートインでは「卓の伝票」が無いと注文できない。
        // お客さんが QR を読んで人数を答えた直後、という状態を作っている。
        table = tableService.createTable("1番テーブル", 4, 10);
        bill = tableService.openSession(table.getId(), 2);

        // ── テスト用のメニューを用意する ─────────────────────────
        // application-test.yml で seed-on-startup: false にしているので、
        // 商品はこのテストが自分で作る。何件あるかを自分で把握できる状態にしておく。
        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        okonomiyaki = menuItemRepository.save(item(category, "肉玉米粉そば", 1180, 12));
        sour = menuItemRepository.save(item(category, "自家製レモンサワー", 850, 2));
    }

    private MenuItem item(Category category, String name, int price, int cookMinutes) {
        MenuItem item = new MenuItem(category, name, price);
        item.setCookMinutes(cookMinutes);
        return item;
    }

    /** 指定の商品だけが入った新しいカートを作る。 */
    private Cart cartOf(MenuItem item, int quantity) {
        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), quantity);
        return cart;
    }

    /** いまの伝票を DB から読み直す（金額は注文のたびに再計算されている）。 */
    private TableSession reloadBill() {
        return tableService.getSession(bill.getId());
    }

    @Nested
    @DisplayName("注文の確定")
    class PlaceOrder {

        @Test
        @DisplayName("カートの中身がそのまま注文になり、卓の伝票にぶら下がる")
        void placesOrderFromCart() {
            Cart cart = new Cart();
            cartService.addToCart(cart, okonomiyaki.getId(), List.of(), 2);
            cartService.addToCart(cart, sour.getId(), List.of(), 1);

            Order order = orderService.placeOrder(cart, bill.getId(), "ソース少なめ");

            // DB に保存されて ID が振られている
            assertThat(order.getId()).isNotNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.RECEIVED);

            // ★イートインの要★ 注文は必ず伝票に結び付く
            assertThat(order.getSession().getId()).isEqualTo(bill.getId());
            // 厨房ボードで「どこに運ぶか」を出すための卓名
            assertThat(order.getTableName()).isEqualTo("1番テーブル");
            assertThat(order.getCustomerName()).isEqualTo("1番テーブル");

            // 営業日は「いまの日付」ではなく<b>伝票の営業日</b>に合わせる。
            // 深夜 0 時をまたいでも、同じ来店の注文は同じ営業日として集計したいため。
            assertThat(order.getBusinessDate()).isEqualTo(bill.getBusinessDate());

            // 明細は商品名・価格を「そのときの値」でコピーして持つ（スナップショット）
            assertThat(order.getLines()).hasSize(2);
            assertThat(order.getLines().get(0).getMenuItemName()).isEqualTo("肉玉米粉そば");
            assertThat(order.getTotalQuantity()).isEqualTo(3);
            assertThat(order.getTotalAmount()).isEqualTo(3210);         // 1180×2 + 850
            assertThat(order.getTaxAmount()).isEqualTo(291);            // 3210 × 10 ÷ 110
            assertThat(order.getEstimatedCookMinutes()).isEqualTo(26);  // 12×2 + 2×1

            assertThat(order.getNote()).isEqualTo("ソース少なめ");

            // お客さん専用 URL のトークンが発行されている
            // （伝票からの「この注文を取り消す」に使う。連番の ID を使うと
            //   番号を変えるだけで他の卓の注文を取り消せてしまう）
            assertThat(order.getPublicToken()).isNotBlank();
            assertThat(orderService.findByToken(order.getPublicToken())).isPresent();
        }

        @Test
        @DisplayName("空のカートでは注文できない")
        void rejectsEmptyCart() {
            assertThatThrownBy(() -> orderService.placeOrder(new Cart(), bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("カートに商品が入っていません");
        }

        @Test
        @DisplayName("受付停止中は OrderRejectedException になり、店長のメッセージが返る")
        void rejectsWhenNotAccepting() {
            // 混雑時のワンタップ停止。ここが効かないと厨房がパンクする。
            setting.setAcceptingOrders(false);
            setting.setClosedMessage("ただいま混み合っております");
            Cart cart = cartOf(okonomiyaki, 1);

            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("ただいま混み合っております");
        }

        @Test
        @DisplayName("ラストオーダー後は注文できない")
        void rejectsAfterLastOrder() {
            // ラストオーダーを過去にずらして「受付終了後」の状態を作る。
            setting.setOpenTime(LocalTime.MIN);
            setting.setLastOrderTime(LocalTime.MIN);  // 00:00 を過ぎていれば受付終了
            Cart cart = cartOf(okonomiyaki, 1);

            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class);
        }

        @Test
        @DisplayName("存在しない伝票 ID を指定すると SessionNotFoundException になる")
        void rejectsUnknownSession() {
            // 画面を長く開いたままにしていた、URL を直接叩かれた、などで起こりうる。
            // 「どこにも属さない注文」が保存されないことを確認する。
            Cart cart = cartOf(okonomiyaki, 1);

            assertThatThrownBy(() -> orderService.placeOrder(cart, 999_999L, null))
                    .isInstanceOf(TableService.SessionNotFoundException.class);
        }

        @Test
        @DisplayName("カートに入れたあとに品切れになった商品があると受け付けない")
        void rejectsSoldOutItem() {
            // カートはセッションに残り続けるので、
            // 「カートに入れる → 迷っている間に売り切れる → 注文ボタンを押す」が普通に起きる。
            // セッションの値を信じて受け付けると、作れない品が伝票に載ってしまう。
            Cart cart = cartOf(okonomiyaki, 1);

            okonomiyaki.setSoldOut(true);  // 厨房が品切れにした、という想定

            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("売り切れ");

            // 注文直前の洗い替えでカートからも取り除かれている
            assertThat(cart.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("値上げされていたら受け付けず、確認しなおしてもらう")
        void rejectsWhenPriceChanged() {
            // 古い価格のまま会計してしまうと、実売価格と伝票が食い違う。
            Cart cart = cartOf(okonomiyaki, 1);

            okonomiyaki.setPrice(1280);

            assertThatThrownBy(() -> orderService.placeOrder(cart, bill.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("価格");

            // 中身は最新の価格に洗い替えられているので、そのまま再確認できる
            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getLines().get(0).getBasePrice()).isEqualTo(1280);
        }
    }

    @Nested
    @DisplayName("伝票への積み上げ")
    class BillTotals {

        @Test
        @DisplayName("追加注文するたびに伝票の小計と請求額が増える")
        void ordersAccumulateIntoOneBill() {
            // イートインでは「とりあえず飲み物 → あとから料理」と何度も注文する。
            // 1 回ごとに新しい伝票ができてしまうと、会計が分かれて事故になる。
            orderService.placeOrder(cartOf(sour, 1), bill.getId(), null);
            orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            TableSession reloaded = reloadBill();

            assertThat(reloaded.getBillableOrders()).hasSize(2);
            assertThat(reloaded.getSubtotalAmount()).isEqualTo(2030);        // 850 + 1180
            assertThat(reloaded.getTableChargeAmount()).isEqualTo(900);      // 450 × 2名
            assertThat(reloaded.getTotalAmount()).isEqualTo(2930);
        }

        @Test
        @DisplayName("店側がキャンセルすると、その注文ぶんが伝票から引かれる")
        void staffCancelReducesTheBill() {
            // ★お金に直結するテスト★
            // 「取り消したのに金額が減らない」は、お客さんが真っ先に気づく不具合。
            Order kept = orderService.placeOrder(cartOf(sour, 1), bill.getId(), null);
            Order canceled = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            orderService.cancelByStaff(canceled.getId(), "材料切れ", "店長");

            TableSession reloaded = reloadBill();
            assertThat(reloaded.getBillableOrders())
                    .extracting(Order::getId)
                    .containsExactly(kept.getId());
            assertThat(reloaded.getSubtotalAmount()).isEqualTo(850);
            assertThat(reloaded.getTotalAmount()).isEqualTo(850 + 900);
        }

        @Test
        @DisplayName("お客さん自身のキャンセルでも伝票の金額が減る")
        void customerCancelReducesTheBill() {
            Order order = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            orderService.cancelByCustomer(order.getPublicToken());

            TableSession reloaded = reloadBill();
            assertThat(reloaded.getSubtotalAmount()).isZero();
            // 注文が全部消えても、テーブルチャージは残る
            assertThat(reloaded.getTotalAmount()).isEqualTo(900);
        }
    }

    @Nested
    @DisplayName("注文番号の採番")
    class OrderNumber {

        @Test
        @DisplayName("その日の1件目は ShopSetting の開始番号、2件目はその+1になる")
        void startsAtConfiguredNumberAndIncrements() {
            // 厨房の伝票と画面の突き合わせに使う番号。
            // 同じ番号が2組に振られると、料理の出し間違いに直結する。
            setting.setOrderNumberStart(501);

            Order first = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            Order second = orderService.placeOrder(cartOf(sour, 1), bill.getId(), null);

            assertThat(first.getOrderNumber()).isEqualTo(501);
            assertThat(second.getOrderNumber()).isEqualTo(502);
        }

        @Test
        @DisplayName("開始番号を変えれば採番もそれに従う")
        void followsSettingValue() {
            setting.setOrderNumberStart(1);

            Order first = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            assertThat(first.getOrderNumber()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("状態の変更")
    class ChangeStatus {

        @Test
        @DisplayName("受付 → 調理中 → 提供可 → 提供済 と進められる")
        void movesThroughTheFlow() {
            Order order = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            Long id = order.getId();

            Order cooking = orderService.changeStatus(id, OrderStatus.COOKING, "店長");
            assertThat(cooking.getStatus()).isEqualTo(OrderStatus.COOKING);
            assertThat(cooking.getCookingStartedAt()).isNotNull();
            assertThat(cooking.getLastHandledBy()).isEqualTo("店長");

            Order ready = orderService.changeStatus(id, OrderStatus.READY, "厨房スタッフ");
            assertThat(ready.getStatus()).isEqualTo(OrderStatus.READY);
            assertThat(ready.getReadyAt()).isNotNull();

            Order completed = orderService.changeStatus(id, OrderStatus.COMPLETED, "ホール");
            assertThat(completed.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(completed.getCompletedAt()).isNotNull();

            // 保存済みの内容として読み直しても同じ状態になっている
            assertThat(orderService.getById(id).getStatus()).isEqualTo(OrderStatus.COMPLETED);
            // 提供済みになっても金額は伝票に残る（会計はまだ先）
            assertThat(reloadBill().getSubtotalAmount()).isEqualTo(1180);
        }

        @Test
        @DisplayName("許可されない遷移はサーバ側で弾かれる（URL を直接叩かれても通らない）")
        void rejectsForbiddenTransition() {
            Order order = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            Long id = order.getId();

            assertThatThrownBy(() -> orderService.changeStatus(id, OrderStatus.COMPLETED, "店長"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(orderService.getById(id).getStatus()).isEqualTo(OrderStatus.RECEIVED);
        }

        @Test
        @DisplayName("存在しない注文 ID を指定すると OrderNotFoundException になる")
        void notFound() {
            assertThatThrownBy(() -> orderService.changeStatus(999_999L, OrderStatus.COOKING, "店長"))
                    .isInstanceOf(OrderService.OrderNotFoundException.class);
        }

        @Test
        @DisplayName("お客さん自身のキャンセルは調理開始前だけ通る")
        void customerCancel() {
            Order order = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            Order canceled = orderService.cancelByCustomer(order.getPublicToken());
            assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
            assertThat(canceled.getCanceledReason()).isEqualTo("お客様都合");

            // 2件目は焼き始めてからキャンセルを試す
            Order second = orderService.placeOrder(cartOf(sour, 1), bill.getId(), null);
            orderService.changeStatus(second.getId(), OrderStatus.COOKING, "店長");

            assertThatThrownBy(() -> orderService.cancelByCustomer(second.getPublicToken()))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("キャンセルできません");
        }
    }

    @Nested
    @DisplayName("厨房ボードへの反映")
    class KitchenBoardView {

        @Test
        @DisplayName("注文が状態ごとの3レーンに振り分けられる")
        void groupsByStatus() {
            // 厨房のタブレットはこの 3 レーンだけを見て動く。
            // 振り分けを間違えると、焼き上がった品が「受付」のまま埋もれる。
            Order a = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            Order b = orderService.placeOrder(cartOf(sour, 1), bill.getId(), null);
            orderService.changeStatus(b.getId(), OrderStatus.COOKING, "店長");

            var board = orderService.kitchenBoard();

            assertThat(board.received()).extracting(Order::getId).containsExactly(a.getId());
            assertThat(board.cooking()).extracting(Order::getId).containsExactly(b.getId());
            assertThat(board.ready()).isEmpty();
            assertThat(board.activeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("厨房ボードのチケットから「どの卓に運ぶか」が分かる")
        void showsWhichTableToServe() {
            // テイクアウトなら番号を呼べば済んだが、イートインでは
            // 卓名が出ていないと料理を運べない。ここが空だと現場が止まる。
            orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);

            assertThat(orderService.kitchenBoard().received())
                    .extracting(Order::getTableName)
                    .containsExactly("1番テーブル");
        }

        @Test
        @DisplayName("提供済み・キャンセルの注文は厨房ボードから消える")
        void closedOrdersDisappear() {
            Order order = orderService.placeOrder(cartOf(okonomiyaki, 1), bill.getId(), null);
            orderService.cancelByStaff(order.getId(), "材料切れ", "店長");

            assertThat(orderService.kitchenBoard().activeCount()).isZero();
            // 一方で当日の一覧には残る（売上・キャンセル記録として必要なため）
            assertThat(orderService.ordersOf(bill.getBusinessDate())).hasSize(1);
        }
    }
}
