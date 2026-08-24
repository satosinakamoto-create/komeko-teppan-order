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
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.dto.KitchenBoard;
import jp.komeko.order.service.dto.WaitEstimate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-08-22 のレビューで見つかった金額まわりの不具合の回帰テスト。
 *
 * <p>対象は 3 つ。いずれも「金額が黙って変わる／消える」系で、
 * 起きてもエラーは出ず、伝票を見た人が気づくしかない。
 *
 * <ol>
 *   <li><b>免除の誤記録</b> … 深夜料金の対象が無い伝票を普通に会計しただけで
 *       lateNightWaived=true が記録され、会計取消→深夜の追加注文の割増が
 *       黙って消えていた（TableService#closeSession）</li>
 *   <li><b>会計済み伝票の変更</b> … 締めた伝票の注文をキャンセル／深夜対象外に
 *       できてしまい、保存済みの合計と明細が食い違った（OrderService）</li>
 *   <li><b>5:00 またぎの厨房ボード</b> … 営業日切替をまたいで開いている伝票の
 *       追加注文が、請求はされるのにボードに出なかった（OrderRepository）</li>
 * </ol>
 *
 * <p><b>このクラスに {@code @Transactional} を付けていない理由</b><br>
 * 本番は「1 リクエスト = 1 トランザクション」で、操作のたびにエンティティを
 * DB から読み直す。テスト全体を 1 つのトランザクションで包むと、
 * 在庫のバルク UPDATE（clearAutomatically）でデタッチされた<b>古いインスタンス</b>を
 * 次の操作がそのまま見てしまい、本番では起きない偽の結果になる
 * （実際、このクラスを最初 @Transactional で書いたら、会計済みガードが
 * 「まだ OPEN の古い伝票」を読んで素通りした）。
 * 作ったデータは {@link #clearAll()} で自分で片付ける（CustomerFlowTest と同じ流儀）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("金額まわりの回帰テスト（2026-08-22 レビュー）")
class BillingFixesIntegrationTest {

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
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository sessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private DiningTable table;
    private TableSession bill;
    private MenuItem okonomiyaki;

    @BeforeEach
    void setUp() {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setAlwaysOpen(false);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setOrderNumberStart(101);
        setting.setTaxRatePercent(10);
        setting.setTableChargePerGuest(450);
        setting.setLateNightSurchargePercent(10);
        applyNightWindow(setting, false);
        shopSettingService.save(setting);

        table = tableService.createTable("1番テーブル", 4, 10);
        bill = tableService.openSession(table.getId(), 2);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem item = new MenuItem(category, "肉玉米粉そば", 1180);
        item.setCookMinutes(12);
        okonomiyaki = menuItemRepository.save(item);
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

    /**
     * 深夜帯の窓を「いま」に対して動かす。
     *
     * <p>深夜料金は注文時刻で判定されるので、窓を実行時刻に合わせて置けば、
     * テストを何時に走らせても結果が変わらない。
     *
     * @param coversNow true なら「いま」が深夜帯に入る（注文すると必ず対象になる）。
     *                  false なら入らない（絶対に対象にならない）
     */
    private void applyNightWindow(ShopSetting setting, boolean coversNow) {
        LocalTime now = LocalTime.now().withNano(0);
        if (coversNow) {
            setting.setLateNightStartTime(now.minusHours(1));
            setting.setLateNightEndTime(now.plusHours(1));
        } else {
            setting.setLateNightStartTime(now.plusHours(2));
            setting.setLateNightEndTime(now.plusHours(3));
        }
    }

    /** 深夜帯の窓だけを動かして保存する（他の設定は setUp のまま）。 */
    private void moveNightWindow(boolean coversNow) {
        ShopSetting setting = shopSettingService.current();
        applyNightWindow(setting, coversNow);
        shopSettingService.save(setting);
    }

    private Order order(MenuItem item, int quantity) {
        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), quantity);
        return orderService.placeOrder(cart, bill.getId(), null);
    }

    private TableSession reloadBill() {
        return tableService.getSession(bill.getId());
    }

    /** 指定した伝票への注文（{@link #order} は setUp で開いた卓に入れる）。 */
    private Order orderOn(TableSession target, MenuItem item, int quantity) {
        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), quantity);
        return orderService.placeOrder(cart, target.getId(), null);
    }

    /**
     * 「前の営業日に開いた、まだ残っている卓」を作る。
     *
     * <p>{@code tableService.openSession} は必ず「いまの営業日」で開くので、
     * 5:00（営業日の切り替え）をまたいだ卓は直接 save して作るしかない。
     * 伝票の営業日は開いた時点で確定し、以後の注文はその値をコピーする。
     */
    private TableSession openBillOn(String tableName, LocalDate businessDate, int sortOrder) {
        DiningTable crossoverTable = tableService.createTable(tableName, 4, sortOrder);
        ShopSetting setting = shopSettingService.current();
        return new TransactionTemplate(transactionManager).execute(status ->
                sessionRepository.save(new TableSession(crossoverTable, businessDate, 2, setting)));
    }

    /**
     * 注文の受付時刻を過去にずらす。
     *
     * <p>{@code createdAt} は会計と提供時間の証跡なので、業務コードから書き換える
     * 手段をわざと用意していない（{@code Order#setCreatedAtForTest} は domain
     * パッケージの中からしか見えない）。ここは別パッケージなので、
     * JPQL のバルク UPDATE で DB の値だけを動かす。
     */
    private void backdate(Order target, Duration age) {
        LocalDateTime moved = LocalDateTime.now().minus(age);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("update Order o set o.createdAt = :moved where o.id = :id")
                        .setParameter("moved", moved)
                        .setParameter("id", target.getId())
                        .executeUpdate());
    }

    /** 厨房ボードの「受付」レーンに出ている注文の ID。 */
    private List<Long> receivedIdsOnBoard() {
        return orderService.kitchenBoard().received().stream().map(Order::getId).toList();
    }

    /** 厨房ボードの 3 レーンすべてに出ている注文の ID。 */
    private List<Long> allIdsOnBoard() {
        KitchenBoard board = orderService.kitchenBoard();
        return Stream.of(board.received(), board.cooking(), board.ready())
                .flatMap(List::stream)
                .map(Order::getId)
                .toList();
    }

    @Nested
    @DisplayName("深夜料金の免除は「実際に外したとき」だけ記録される")
    class LateNightWaiverRecording {

        @Test
        @DisplayName("対象が無い伝票を普通に会計しても、免除は記録されない")
        void ordinaryDaytimeCloseDoesNotRecordAWaiver() {
            order(okonomiyaki, 1);   // 深夜帯の外の注文

            // チェックボックスは対象が無いので初期状態から外れている。
            // その状態のまま締める＝ applyLateNight=false で届く（HallController#close）
            tableService.closeSession(bill.getId(), false, "店長", null);

            assertThat(reloadBill().isLateNightWaived())
                    .as("誰も免除の判断をしていないのに waived が立つと、"
                            + "開け直し後の深夜の追加注文から割増が黙って消える")
                    .isFalse();
        }

        @Test
        @DisplayName("会計取消→深夜帯の追加注文には、ちゃんと割増が付く")
        void surchargeAppliesToNightOrdersAfterReopen() {
            order(okonomiyaki, 1);
            tableService.closeSession(bill.getId(), false, "店長", null);   // 昼の通常会計
            tableService.reopenSession(bill.getId(), "店長");               // 誤会計に気づいた

            // ここからは深夜帯（判定は注文時刻なので、窓をいまに合わせれば必ず対象になる）
            moveNightWindow(true);
            order(okonomiyaki, 1);   // 23 時すぎの追加注文に相当

            TableSession reloaded = reloadBill();
            assertThat(reloaded.getLateNightAmount())
                    .as("修正前はここが 0 だった（1 回目の会計が waived=true を記録していたため）")
                    .isPositive();
        }

        @Test
        @DisplayName("対象がある伝票でチェックを外して締めたら、免除として記録・維持される")
        void intentionalWaiverIsRecordedAndSurvivesReopen() {
            moveNightWindow(true);
            order(okonomiyaki, 1);   // 深夜帯の注文

            // スタッフが意図してチェックを外した（常連さんへのサービスなど）
            tableService.closeSession(bill.getId(), false, "店長", null);

            TableSession closed = reloadBill();
            assertThat(closed.isLateNightWaived()).isTrue();
            assertThat(closed.getLateNightAmount()).isZero();

            // 開け直しても、人の判断（免除）は計算で上書きされない（仕様 §4.2）
            tableService.reopenSession(bill.getId(), "店長");
            assertThat(reloadBill().getLateNightAmount()).isZero();
        }
    }

    @Nested
    @DisplayName("会計済みの伝票は、金額の根拠ごと凍結される")
    class ClosedBillIsFrozen {

        @Test
        @DisplayName("会計済み伝票の注文は、スタッフでもキャンセルできない")
        void staffCannotCancelOrdersOnAClosedBill() {
            Order placed = order(okonomiyaki, 1);
            tableService.closeSession(bill.getId(), false, "店長", null);

            assertThatThrownBy(() -> orderService.cancelByStaff(placed.getId(), "材料切れ", "店長"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("会計済み");

            // 汎用の状態変更経由でも同じ（厨房ボードのキャンセルボタンはこちらを通る）
            assertThatThrownBy(() -> orderService.changeStatus(placed.getId(), OrderStatus.CANCELED, "店長"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("会計済み");
        }

        @Test
        @DisplayName("お客さま自身のキャンセルも、お客さま向けの文言で断られる")
        void customerCancelIsRejectedWithACustomerFacingMessage() {
            Order placed = order(okonomiyaki, 1);
            tableService.closeSession(bill.getId(), false, "店長", null);

            assertThatThrownBy(() -> orderService.cancelByCustomer(placed.getPublicToken()))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("お会計");
        }

        @Test
        @DisplayName("深夜料金の対象外トグルも、会計済みなら拒否される")
        void lateNightExemptToggleIsRejectedOnAClosedBill() {
            Order placed = order(okonomiyaki, 1);
            tableService.closeSession(bill.getId(), false, "店長", null);

            // 画面はボタンを出していないが、古いタブや直接 POST からは届く。
            // ここを通すと、開け直したときの再計算で確定済みの請求額が変わる
            assertThatThrownBy(() -> orderService.setLateNightExempt(placed.getId(), true, "店長"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("会計済み");
        }

        @Test
        @DisplayName("調理の進行（焼きはじめ・焼き上がり）は会計後でも許される")
        void cookingProgressIsStillAllowedAfterClose() {
            Order placed = order(okonomiyaki, 1);
            tableService.closeSession(bill.getId(), false, "店長", null);

            // 会計とキッチンの進行は独立。先にレジを済ませて料理を待つ卓は普通にある
            Order cooking = orderService.changeStatus(placed.getId(), OrderStatus.COOKING, "焼き場");
            assertThat(cooking.getStatus()).isEqualTo(OrderStatus.COOKING);
        }
    }

    @Nested
    @DisplayName("営業日切替（5:00）をまたいで開いている伝票")
    class BusinessDateCrossover {

        @Test
        @DisplayName("前営業日の伝票への追加注文も、厨房ボードに出る")
        void ordersOnAnOpenBillFromThePreviousBusinessDayAppearOnTheBoard() {
            // 「今夜だけ朝までやる」で 5:00 をまたいだ卓を再現する。
            // 伝票の営業日は開いた時点で確定し、以後の注文はその値をコピーする
            LocalDate previousBusinessDay = LocalDate.now().minusDays(1);
            DiningTable allNightTable = tableService.createTable("朝まで卓", 4, 20);
            ShopSetting setting = shopSettingService.current();
            TableSession allNightBill = new TransactionTemplate(transactionManager).execute(status ->
                    sessionRepository.save(
                            new TableSession(allNightTable, previousBusinessDay, 2, setting)));

            Cart cart = new Cart();
            cartService.addToCart(cart, okonomiyaki.getId(), List.of(), 1);
            Order placed = orderService.placeOrder(cart, allNightBill.getId(), null);

            // 注文は前営業日の日付を持つ（これ自体は仕様どおり）
            assertThat(placed.getBusinessDate()).isEqualTo(previousBusinessDay);

            // 修正前は currentBusinessDate（＝今日）で絞っていたため、
            // この注文はどのレーンにも出なかった＝厨房が存在を知り得なかった
            List<Integer> receivedNumbers = orderService.kitchenBoard().received().stream()
                    .map(Order::getOrderNumber)
                    .toList();
            assertThat(receivedNumbers).contains(placed.getOrderNumber());
        }

        @Test
        @DisplayName("会計を締めた卓の未提供注文も、切替後のボードに残る")
        void unservedOrdersStayOnTheBoardEvenAfterTheBillIsClosed() {
            // 4:50 に会計を済ませ、焼き待ちの品を残したまま 5:00 をまたいだ卓。
            // closeSession に未提供注文のガードは無い（会計と調理は独立＝厨房の実態）
            LocalDate previousBusinessDay = LocalDate.now().minusDays(1);
            TableSession crossoverBill = openBillOn("朝まで卓（会計済み）", previousBusinessDay, 21);
            Order placed = orderOn(crossoverBill, okonomiyaki, 1);
            tableService.closeSession(crossoverBill.getId(), false, "店長", null);
            backdate(placed, Duration.ofHours(2));   // 切替から 2 時間後に厨房が見ている

            assertThat(placed.getStatus()).isEqualTo(OrderStatus.RECEIVED);
            assertThat(receivedIdsOnBoard())
                    .as("修正前は「前営業日 かつ CLOSED」でどちらの条件にも当たらず、"
                            + "全レーンから消えていた。請求は済んでいるので金は消えないが、"
                            + "厨房が焼くべき品の存在を知り得なくなる")
                    .contains(placed.getId());
        }

        @Test
        @DisplayName("提供済みの注文は、切替をまたいでもボードに出てこない")
        void servedOrdersDoNotComeBackAfterTheCutover() {
            LocalDate previousBusinessDay = LocalDate.now().minusDays(1);
            TableSession crossoverBill = openBillOn("朝まで卓（提供済み）", previousBusinessDay, 22);
            Order placed = orderOn(crossoverBill, okonomiyaki, 1);

            orderService.changeStatus(placed.getId(), OrderStatus.READY, "焼き場");
            orderService.changeStatus(placed.getId(), OrderStatus.COMPLETED, "ホール");
            tableService.closeSession(crossoverBill.getId(), false, "店長", null);

            assertThat(allIdsOnBoard())
                    .as("持ち越しを拾う条件を広げたせいで、お渡し済みの品まで復活しては困る")
                    .doesNotContain(placed.getId());
        }

        @Test
        @DisplayName("持ち越しの窓より古い焼き忘れは、伝票が開いたままでもボードに出ない")
        void staleUnservedOrdersFallOffTheBoard() {
            TableSession forgottenBill = openBillOn("締め忘れ卓", LocalDate.now().minusDays(3), 23);
            Order forgotten = orderOn(forgottenBill, okonomiyaki, 1);
            backdate(forgotten, Duration.ofHours(30));   // 持ち越しの窓（6 時間）より前

            assertThat(allIdsOnBoard())
                    .as("「伝票が OPEN なら日付を問わず出す」だと、締め忘れた卓の注文が"
                            + "いつまでも居座り、今夜の仕事が埋もれる（これも別の事故）")
                    .doesNotContain(forgotten.getId());
        }

        @Test
        @DisplayName("待ち時間の目安は、厨房ボードと同じ母集合を数える")
        void waitEstimateCountsTheSameOrdersAsTheBoard() {
            // 切替前に入り、まだ焼かれていない注文（会計は済んでいる）
            LocalDate previousBusinessDay = LocalDate.now().minusDays(1);
            TableSession crossoverBill = openBillOn("朝まで卓（またぎ）", previousBusinessDay, 24);
            Order crossover = orderOn(crossoverBill, okonomiyaki, 1);
            tableService.closeSession(crossoverBill.getId(), false, "店長", null);
            backdate(crossover, Duration.ofMinutes(30));

            // 切替後、新しい営業日に入った注文
            Order afterCutover = order(okonomiyaki, 1);

            assertThat(receivedIdsOnBoard())
                    .as("ボードでは、またぎ卓の注文が前に並んでいる")
                    .containsExactly(crossover.getId(), afterCutover.getId());

            WaitEstimate estimate = orderService.estimateWait(orderService.getById(afterCutover.getId()));
            assertThat(estimate.waitingOrders())
                    .as("修正前はここが 0 だった（営業日の厳密一致で数えていたため）。"
                            + "実際に鉄板を占領しているのはボードに出ている注文なので、"
                            + "お客さま画面の「あと ◯ 組」だけが実態より少なく出ていた")
                    .isEqualTo(1);
        }
    }
}
