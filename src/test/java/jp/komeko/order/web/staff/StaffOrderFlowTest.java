package jp.komeko.order.web.staff;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店舗版スマホ注文（設計「店舗版スマホ注文_仕様_2026-09-06」）。
 *
 * <p><b>このテストが守っているもの＝「送った注文が、押した番号の伝票に載ること」</b><br>
 * 店員は卓を渡り歩きます。お客さまのスマホは QR で卓に固定されますが、
 * こちらは番号を押し替えるので、<b>積んだ品が別の卓に化ける</b>経路があります。
 * 化けても例外は出ず、金額も自然に見えます。気づくのはお会計のときです。
 *
 * <p>もうひとつは時価の金額です。¥0 のまま通ると、
 * お客さまは召し上がったのに請求されず、伝票を眺めても気づけません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("店舗版スマホ注文")
class StaffOrderFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    private MockHttpSession terminal;
    private DiningTable seatA;
    private DiningTable seatB;
    private DiningTable empty;
    private MenuItem beer;
    private MenuItem steak;

    @BeforeEach
    void setUp() {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setTableChargePerGuest(0);
        setting.setLateNightSurchargePercent(0);
        setting.setOrderNumberStart(101);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("鉄板焼き", 10));
        beer = menuItemRepository.save(new MenuItem(category, "生ビール（中）", 580));

        // 実店舗と同じ登録。時価の品は価格 0 で、売り切れとして入っている
        MenuItem s = new MenuItem(category, "国産牛サーロインステーキ", 0);
        s.setSoldOut(true);
        steak = menuItemRepository.save(s);

        seatA = diningTableRepository.save(new DiningTable("カウンター札2", 6, 10));
        seatB = diningTableRepository.save(new DiningTable("カウンター札5", 6, 20));
        empty = diningTableRepository.save(new DiningTable("テーブル1", 4, 30));

        tableService.openSession(seatA.getId(), 3);
        tableService.openSession(seatB.getId(), 2);

        terminal = new MockHttpSession();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        diningTableRepository.deleteAll();
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
        dailyCounterRepository.deleteAllInBatch();
    }

    /** 番号を選ぶ（店舗の端末で、その卓を開いた状態にする）。 */
    private void select(DiningTable table) throws Exception {
        mockMvc.perform(get("/staff/order/seats/" + table.getId()).session(terminal));
    }

    /** 品を積む。 */
    private void add(MenuItem item, int quantity, Integer price) throws Exception {
        var request = post("/staff/order/cart/add").with(csrf()).session(terminal)
                .param("menuItemId", String.valueOf(item.getId()))
                .param("quantity", String.valueOf(quantity));
        if (price != null) {
            request = request.param("price", String.valueOf(price));
        }
        mockMvc.perform(request);
    }

    private TableSession billOf(DiningTable table) {
        return tableService.currentSession(table.getId()).orElseThrow();
    }

    // ========================================================================

    @Nested
    @DisplayName("番号の盤面")
    @WithMockUser(roles = "STAFF", username = "やまだ")
    class Board {

        @Test
        @DisplayName("★ 空席も含めて全部の番号が出る（ご案内のために別画面へ行かせない）")
        void showsEveryNumberIncludingEmptySeats() throws Exception {
            String page = mockMvc.perform(get("/staff/order").session(terminal))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(page).contains("カウンター札2").contains("カウンター札5");
            assertThat(page).as("空席が出ていない").contains("テーブル1").contains("空席");
        }

        @Test
        @DisplayName("★ 使用中の番号には人数・入店時刻・金額が出る（隣の組と見分ける材料）")
        void busySeatsCarryEnoughToTellThemApart() throws Exception {
            select(seatA);
            add(beer, 2, null);
            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            String page = mockMvc.perform(get("/staff/order").session(terminal))
                    .andReturn().getResponse().getContentAsString();

            // 番号だけだと、カウンターで組が隣り合ったときに見分けられない
            assertThat(page).as("人数が出ていない").contains("3名");
            assertThat(page).as("金額が出ていない").contains("1,160");
        }
    }

    @Nested
    @DisplayName("注文を送る")
    @WithMockUser(roles = "STAFF", username = "やまだ")
    class Placing {

        @Test
        @DisplayName("★ 押した番号の伝票に載る")
        void ordersLandOnTheSelectedSeat() throws Exception {
            select(seatA);
            add(beer, 2, null);

            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal))
                    .andExpect(redirectedUrl("/staff/order"));

            assertThat(billOf(seatA).getSubtotalAmount()).isEqualTo(1160);
            assertThat(billOf(seatB).getSubtotalAmount()).as("隣の卓に載っている").isZero();
        }

        @Test
        @DisplayName("★ 何品積んでも、厨房には伝票 1 枚で出る")
        void sendsEverythingAsOneTicket() throws Exception {
            select(seatA);
            add(beer, 2, null);
            add(steak, 1, 6800);

            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            // 1 品ずつ送る作りだと、着席時の 5 品注文が厨房に 5 枚並ぶ。
            // ★ 明細は遅延読み込みなので、ここで getLines() を触ると
            //   LazyInitializationException になる（open-in-view: false）。
            //   件数と金額で「1 枚に 2 品が入った」ことを確かめる
            List<Order> orders = orderRepository.findAll();
            assertThat(orders).as("注文が分かれている").hasSize(1);
            assertThat(billOf(seatA).getSubtotalAmount())
                    .as("2 品ぶんが 1 枚に入っていない")
                    .isEqualTo(580 * 2 + 6800);
        }

        @Test
        @DisplayName("★ 入れた店員の名前が残る")
        void recordsWhoEnteredIt() throws Exception {
            select(seatA);
            add(beer, 1, null);
            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            assertThat(orderRepository.findAll().get(0).getPlacedBy()).isNotBlank();
            assertThat(orderRepository.findAll().get(0).isPlacedByStaff()).isTrue();
        }

        @Test
        @DisplayName("送ったらカートは空になる（次の卓へそのまま移れる）")
        void clearsTheCartAfterSending() throws Exception {
            select(seatA);
            add(beer, 1, null);
            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            // 空でなければ、次の番号を押したときに「送っていない品があります」と出てしまう
            mockMvc.perform(get("/staff/order/seats/" + seatB.getId()).session(terminal))
                    .andExpect(redirectedUrl("/menu"));
        }
    }

    @Nested
    @DisplayName("卓を渡り歩く")
    @WithMockUser(roles = "STAFF", username = "やまだ")
    class SwitchingSeats {

        @Test
        @DisplayName("★ 送っていない品を持ったまま別の番号を押したら止める")
        void stopsWhenCarryingUnsentItems() throws Exception {
            select(seatA);
            add(beer, 2, null);

            // 黙って持ち越すと、札2 のビールが札5 の伝票に化ける。
            // 化けても例外は出ず、金額も自然に見える
            String page = mockMvc.perform(get("/staff/order/seats/" + seatB.getId()).session(terminal))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(page).contains("カウンター札2").contains("カウンター札5");
            assertThat(page).as("破棄されることを書いていない").contains("破棄");
            assertThat(billOf(seatB).getSubtotalAmount()).as("移る前に載ってしまった").isZero();
        }

        @Test
        @DisplayName("同じ番号を押し直したときは止めない（続きなので）")
        void doesNotStopOnTheSameSeat() throws Exception {
            select(seatA);
            add(beer, 1, null);

            mockMvc.perform(get("/staff/order/seats/" + seatA.getId()).session(terminal))
                    .andExpect(redirectedUrl("/menu"));
        }

        @Test
        @DisplayName("★ 破棄を選んだら、前の卓には何も残らない")
        void discardLeavesNothingBehind() throws Exception {
            select(seatA);
            add(beer, 2, null);

            mockMvc.perform(get("/staff/order/seats/" + seatB.getId())
                            .param("discard", "true").session(terminal))
                    .andExpect(redirectedUrl("/menu"));

            assertThat(billOf(seatA).getSubtotalAmount()).as("破棄したのに載っている").isZero();
            assertThat(billOf(seatB).getSubtotalAmount()).isZero();
        }
    }

    @Nested
    @DisplayName("時価の品")
    @WithMockUser(roles = "STAFF", username = "やまだ")
    class MarketPriced {

        @Test
        @DisplayName("★ 金額を入れれば積める（売り切れ登録されていても）")
        void canBeAddedWithAPrice() throws Exception {
            select(seatA);
            add(steak, 1, 6800);

            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            // 時価の「売り切れ」は「今日はもう無い」ではなく「値段が未定」の意味
            assertThat(billOf(seatA).getSubtotalAmount()).isEqualTo(6800);
        }

        @Test
        @DisplayName("★ 金額を入れずには積めない（¥0 がカートに入らない）")
        void cannotBeAddedWithoutAPrice() throws Exception {
            select(seatA);
            add(steak, 1, null);

            // 送る段で断るのではなく、積む段で止める。
            // ¥0 の時価がカートに一瞬でも存在しなければ、送り忘れも
            // お客さまの伝票に ¥0 が出ることも起きようがない
            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));
            assertThat(orderRepository.findAll()).as("¥0 のまま通った").isEmpty();
        }

        @Test
        @DisplayName("★ 入れた金額が、そのまま請求額になる（送信時に 0 へ戻らない）")
        void keepsThePriceThroughTheRefresh() throws Exception {
            select(seatA);
            add(steak, 2, 6800);

            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));

            // 送信の直前にカートを洗い替える処理がある（値上げ・品切れの最終確認）。
            // そこで時価の品をマスタ価格（0 円）で作り直すと、金額が消える。
            // 例外も出ないので、お客さまの伝票で初めて分かる
            assertThat(billOf(seatA).getSubtotalAmount()).isEqualTo(13_600);
        }

        @Test
        @DisplayName("ふつうの品に金額を付けたら断る（黙って値引きさせない）")
        void refusesPriceOverrideOnNormalItems() throws Exception {
            select(seatA);
            add(beer, 1, 100);

            mockMvc.perform(post("/staff/order/submit").with(csrf()).session(terminal));
            assertThat(orderRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("空席へのご案内")
    @WithMockUser(roles = "STAFF", username = "やまだ")
    class Seating {

        @Test
        @DisplayName("★ 人数を入れると伝票が開いて、そのまま注文へ進める")
        void opensTheBillAndGoesStraightToTheMenu() throws Exception {
            mockMvc.perform(get("/staff/order/seats/" + empty.getId()).session(terminal))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/staff/order/seats/" + empty.getId() + "/open")
                            .with(csrf()).session(terminal).param("guestCount", "4"))
                    .andExpect(redirectedUrl("/menu"));

            assertThat(billOf(empty).getGuestCount()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("入れる人を絞る")
    class Access {

        @Test
        @DisplayName("★ ログインしていない人は開けない")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/staff/order"))
                    .andExpect(status().is3xxRedirection());
        }

        @Test
        @WithMockUser(roles = {"STAFF", "GUEST"})
        @DisplayName("★ 見学者（公開デモ）は開けない")
        void refusesGuests() throws Exception {
            // ここから送った注文は本物の伝票に載り、請求額が動く。
            // 時価の品には金額まで入れられる
            mockMvc.perform(get("/staff/order"))
                    .andExpect(status().isForbidden());
        }
    }
}
