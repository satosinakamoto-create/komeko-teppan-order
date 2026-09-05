package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OptionChoice;
import jp.komeko.order.domain.OptionGroup;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.SettlementMethod;
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
 * スタッフが卓に代わって注文を入れる経路（{@code OrderService#placeByStaff}）のテスト。
 *
 * <p><b>この経路が守っているもの＝「人が決めた金額が、決めたとおりに請求されること」</b><br>
 * 時価の品（国産牛ステーキなど）は、その日の仕入れを見ないと金額が決まりません。
 * スタッフはお客さまの目の前で「今日は 6,800 円です」と伝えます。
 * その金額がそのまま伝票に載らなければ、<b>口頭で伝えた額とお会計の額が食い違います</b>。
 * これは謝って済む話ではなく、レジで揉める話です。
 *
 * <p><b>なぜ判断をお客さま経路と分けたのか</b><br>
 * 時価の品は「価格 0 円のまま注文されるのを防ぐため」に、
 * はじめから<b>売り切れとして登録</b>されています（{@code DataSeeder}）。
 * つまりこの品にとっての売り切れは「今日はもう無い」ではなく
 * 「まだ値段が決まっていない」の意味です。
 * 金額を入れるこの画面では、その理由が解消されています。
 * ——という判断は、お客さまの画面には持ち込めません。だから経路を分けました。
 *
 * <p>分けた以上、<b>緩めてはいけないところ</b>も固定しておく必要があります。
 * 残数（売り越し）と、時価でない品の売り切れと、選択肢の必須は緩めていません。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("スタッフが卓に代わって入れる注文")
class StaffOrderEntryTest {

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

    private DiningTable table;
    private TableSession bill;

    /** ふつうの品（価格が決まっている）。 */
    private MenuItem okonomiyaki;
    /** 時価の品。価格 0 で、売り切れとして登録されている（実店舗と同じ状態）。 */
    private MenuItem steak;

    @BeforeEach
    void setUp() {
        // 採番カウンタは REQUIRES_NEW で払い出されるためロールバックされない。
        // 別トランザクションで先に消しておく（OrderServiceIntegrationTest と同じ理由）
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.executeWithoutResult(status -> dailyCounterRepository.deleteAllInBatch());

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setOrderNumberStart(101);
        setting.setTaxRatePercent(10);
        setting.setTableChargePerGuest(0);          // 金額の検証にチャージを混ぜない
        setting.setLateNightSurchargePercent(0);    // 実行時刻で金額がぶれないように

        table = tableService.createTable("1番テーブル", 4, 10);
        bill = tableService.openSession(table.getId(), 2);

        Category category = categoryRepository.save(new Category("鉄板焼き", 10));

        okonomiyaki = new MenuItem(category, "肉玉米粉そば", 1180);
        okonomiyaki.setCookMinutes(12);
        okonomiyaki = menuItemRepository.save(okonomiyaki);

        // 実店舗の登録と同じ形にする。ここを「売り切れでない」にしてしまうと、
        // このテストは<b>いちばん起こる場面を通らなくなる</b>
        steak = new MenuItem(category, "国産牛サーロインステーキ", 0);
        steak.setDescription("時価。仕入れ状況により価格が変わります。");
        steak.setCookMinutes(15);
        steak.setSoldOut(true);
        steak = menuItemRepository.save(steak);
    }

    private TableSession reloadBill() {
        return tableService.getSession(bill.getId());
    }

    // ========================================================================

    @Nested
    @DisplayName("時価の品")
    class MarketPriced {

        @Test
        @DisplayName("★ 売り切れとして登録されていても、金額を入れれば通る（この画面がある理由）")
        void acceptsSoldOutMarketPricedItemWithPrice() {
            Order order = orderService.placeByStaff(
                    bill.getId(), steak.getId(), List.of(), 1, 6800, "ミディアムレアで", "やまだ");

            assertThat(order.getOrderNumber()).isEqualTo(101);
            assertThat(order.getLines()).hasSize(1);
            assertThat(order.getLines().get(0).getMenuItemName()).isEqualTo("国産牛サーロインステーキ");
        }

        @Test
        @DisplayName("★ 伝えた金額が、そのまま請求額になる")
        void chargesExactlyThePriceTheStaffQuoted() {
            orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 2, 6800, null, "やまだ");

            // 口頭で「6,800 円」と伝えた品を 2 つ。13,600 円以外の数字が出たら、
            // それはレジで揉める金額になる
            assertThat(reloadBill().getTotalAmount()).isEqualTo(13_600);
        }

        @Test
        @DisplayName("★ 金額を入れずには通せない（0 円の注文を伝票に載せない）")
        void refusesWithoutAPrice() {
            // 空のまま通ると、お客さまは召し上がったのに請求されない。
            // しかも金額が 0 なので、伝票を眺めても気づきにくい
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, null, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("時価");

            assertThat(reloadBill().getBillableOrders()).isEmpty();
        }

        @Test
        @DisplayName("0 円以下は通さない")
        void refusesZeroOrNegative() {
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 0, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class);
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, -100, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class);
        }

        @Test
        @DisplayName("★ 桁を 1 つ多く打った金額は止める")
        void stopsAnExtraZero() {
            // 6,800 のつもりで 68,000 は通る（人が読んで気づく領域）。
            // ここで止めたいのは 680,000 のような、明らかに桁が違うもの。
            // 完全には防げないが、「0 を 1 つ余分に付けた」の大半はここで止まる
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 680_000, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("大きすぎます");
        }
    }

    @Nested
    @DisplayName("緩めていないところ")
    class StillGuarded {

        @Test
        @DisplayName("★ 時価でない品に金額を付けたら断る（黙って値引きさせない）")
        void refusesPriceOverrideOnNormalItems() {
            // 黙って捨てると、スタッフは値引きしたつもりで送信でき、
            // 画面には「入れました」と出る。食い違いに気づくのはお会計のときで、
            // そのときにはもうお客さまに別の金額を伝えたあと
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), okonomiyaki.getId(), List.of(), 1, 500, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("金額は変更できません");

            assertThat(reloadBill().getBillableOrders()).isEmpty();
        }

        @Test
        @DisplayName("★ 時価でない品の売り切れは通さない（厨房が作れないものを伝票に載せない）")
        void refusesSoldOutNormalItems() {
            okonomiyaki.setSoldOut(true);
            menuItemRepository.save(okonomiyaki);

            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), okonomiyaki.getId(), List.of(), 1, null, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("販売を再開");
        }

        @Test
        @DisplayName("★ 残数は時価でも尊重する（スタッフだから通す、にしない）")
        void neverOversellsEvenForStaff() {
            steak.setStockRemaining(1);
            menuItemRepository.save(steak);

            orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ");

            // 残り 0。ここを通すと、厨房に無いものを 2 人ぶん請求することになる
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class);

            assertThat(menuItemRepository.findById(steak.getId()).orElseThrow().getStockRemaining())
                    .isZero();
        }

        @Test
        @DisplayName("★ 選択肢の必須は、お客さま側とまったく同じに効く")
        void appliesTheSameOptionRules() {
            // スタッフ側だけ緩めると、お客さまの画面では作れない組み合わせが伝票に載る。
            // オプションには追加料金があるので、それは請求額の食い違いとして表に出る
            MenuItem course = new MenuItem(categoryRepository.save(new Category("コース", 20)), "おまかせ", 0);
            OptionGroup group = new OptionGroup("焼き加減", 1, 1, 10);
            group.addChoice(new OptionChoice("レア", 0, 10));
            group.addChoice(new OptionChoice("ミディアム", 0, 20));
            course.addOptionGroup(group);
            MenuItem saved = menuItemRepository.save(course);

            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), saved.getId(), List.of(), 1, 5000, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("焼き加減");
        }

        @Test
        @DisplayName("★ 会計が済んだ伝票には入れられない")
        void refusesClosedBills() {
            tableService.closeSession(bill.getId(), false, "やまだ", null, SettlementMethod.CASH);

            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("お会計");
        }

        @Test
        @DisplayName("お会計待ちの伝票にも入れられない（再開してから入れる）")
        void refusesClosingBills() {
            tableService.startCheckout(bill.getId());

            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("再開");
        }
    }

    @Nested
    @DisplayName("誰が入れたかの記録")
    class Attribution {

        @Test
        @DisplayName("★ 入れたスタッフ名が注文に残る")
        void recordsWhoEnteredIt() {
            // 時価はスタッフが金額を決める＝請求額の一部を人が決めている。
            // あとから「この 6,800 円は誰が決めたのか」を確かめられないと、
            // 打ち間違いも意図的な操作も追えない
            Order order = orderService.placeByStaff(
                    bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ");

            assertThat(order.getPlacedBy()).isEqualTo("やまだ");
            assertThat(order.isPlacedByStaff()).isTrue();
        }

        @Test
        @DisplayName("★ お客さまがご自分で入れた注文は空のまま")
        void leavesCustomerOrdersBlank() {
            // 「お客さま」のような文字列を入れると、
            // 同じ名前のスタッフがいたときに区別できなくなる。
            // 空であること自体が「お客さま入力」の意味
            Cart cart = new Cart();
            cartService.addToCart(cart, okonomiyaki.getId(), List.of(), 1);
            Order order = orderService.placeOrder(cart, bill.getId(), null);

            assertThat(order.getPlacedBy()).isNull();
            assertThat(order.isPlacedByStaff()).isFalse();
        }
    }

    @Nested
    @DisplayName("入れたあと")
    class AfterEntry {

        @Test
        @DisplayName("★ 厨房ボードに、お客さまの注文と同じように出る")
        void appearsOnTheKitchenBoard() {
            // スタッフ入力を別枠にすると、厨房は 2 つの場所を見ることになる。
            // 忙しい時間帯に片方を見落とせば、その品は焼かれない
            orderService.placeByStaff(bill.getId(), steak.getId(), List.of(), 1, 6800, "レアで", "やまだ");

            assertThat(orderService.kitchenBoard().received())
                    .extracting(Order::getTableName)
                    .contains("1番テーブル");
        }

        @Test
        @DisplayName("備考（焼き加減）が注文に残り、厨房に届く")
        void keepsTheNote() {
            Order order = orderService.placeByStaff(
                    bill.getId(), steak.getId(), List.of(), 1, 6800, "ミディアムレアで", "やまだ");

            assertThat(order.getNote()).isEqualTo("ミディアムレアで");
        }

        @Test
        @DisplayName("卓の名前が入る（厨房は「どの卓へ運ぶか」を見ている）")
        void namesTheTable() {
            Order order = orderService.placeByStaff(
                    bill.getId(), steak.getId(), List.of(), 1, 6800, null, "やまだ");

            assertThat(order.getCustomerName()).isEqualTo("1番テーブル");
        }

        @Test
        @DisplayName("個数の上限を超えたら断る")
        void refusesTooManyAtOnce() {
            assertThatThrownBy(() ->
                    orderService.placeByStaff(bill.getId(), steak.getId(), List.of(),
                            Cart.MAX_QUANTITY_PER_LINE + 1, 6800, null, "やまだ"))
                    .isInstanceOf(OrderRejectedException.class);
        }
    }
}
