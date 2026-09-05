package jp.komeko.order.web.hall;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.SettlementMethod;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * スタッフが卓に代わって注文を入れる<b>画面</b>のテスト。
 *
 * <p>判断そのものは {@code StaffOrderEntryTest} が固定しています。
 * ここで見るのは<b>その判断に手が届くか</b>です。
 * サービスが正しくても、金額の欄が出ていなければ時価の品は入れられませんし、
 * 入口のリンクが無ければその画面には辿り着けません。
 * どちらの壊れ方も例外を出さず、画面は 200 で返ります。
 *
 * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
 * 本番と同じ形で描画させるためです（{@code HallClosingBadgeTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("スタッフの注文入力画面")
class StaffOrderEntryPageTest {

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

    private TableSession bill;
    private MenuItem steak;
    private MenuItem okonomiyaki;

    @BeforeEach
    void setUp() {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        setting.setTableChargePerGuest(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("鉄板焼き", 10));

        // 実店舗と同じ登録。時価の品は価格 0 で、売り切れとして入っている
        steak = new MenuItem(category, "国産牛サーロインステーキ", 0);
        steak.setSoldOut(true);
        steak = menuItemRepository.save(steak);

        okonomiyaki = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", 1180));

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        bill = tableService.openSession(table.getId(), 2);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    /** 参照している側から先に消す（外部キー制約）。 */
    private void clearAll() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        diningTableRepository.deleteAll();
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
        dailyCounterRepository.deleteAllInBatch();
    }

    private String html(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ========================================================================

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 伝票の画面に入口がある（無ければ辿り着けない）")
    void offersAnEntryPointOnTheBill() throws Exception {
        String page = html("/hall/bills/" + bill.getId());

        assertThat(page).contains("/hall/bills/" + bill.getId() + "/orders/new");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 時価の品も選べる（売り切れとして登録されているので、隠すと永久に選べない）")
    void listsMarketPricedItemsEvenThoughTheyAreSoldOut() throws Exception {
        String page = html("/hall/bills/" + bill.getId() + "/orders/new");

        assertThat(page).as("時価の品が一覧に無い").contains("国産牛サーロインステーキ");
        assertThat(page).as("ふつうの品が一覧に無い").contains("肉玉米粉そば");
        // 0 円の品に「¥0」と出すと、ただの安い品に見える
        assertThat(page).as("時価と分かる表示が無い").contains("時価");
        // この画面がある理由が時価なので、94 品のカテゴリ一覧に埋めない。
        // 埋めると、いちばん使う操作のたびにスクロールして探すことになる
        assertThat(page).as("時価の品を先頭に別枠で出していない").contains("時価・おまかせ");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 品切れの品には一覧の時点で目印を出す（選んでから断らない）")
    void marksSoldOutItemsInTheList() throws Exception {
        okonomiyaki.setSoldOut(true);
        menuItemRepository.save(okonomiyaki);

        String page = html("/hall/bills/" + bill.getId() + "/orders/new");

        assertThat(page).as("品切れの目印が無い").contains("品切れ");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 時価の品を選ぶと、金額の欄が出る（この画面がある理由）")
    void showsThePriceFieldForMarketPricedItems() throws Exception {
        String page = html("/hall/bills/" + bill.getId() + "/orders/new?itemId=" + steak.getId());

        assertThat(page).as("金額の入力欄が無い").contains("name=\"price\"");
        assertThat(page).as("送信できない").contains("この内容で注文を入れる");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ ふつうの品には金額の欄を出さない（値引きの口にしない）")
    void hidesThePriceFieldForNormalItems() throws Exception {
        String page = html("/hall/bills/" + bill.getId() + "/orders/new?itemId=" + okonomiyaki.getId());

        assertThat(page).as("ふつうの品に金額欄が出ている").doesNotContain("name=\"price\"");
        assertThat(page).contains("¥1,180");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ ふつうの品が売り切れなら、送信ボタンを出さずに直し方を書く")
    void blocksSoldOutNormalItemsWithAWayOut() throws Exception {
        okonomiyaki.setSoldOut(true);
        menuItemRepository.save(okonomiyaki);

        String page = html("/hall/bills/" + bill.getId() + "/orders/new?itemId=" + okonomiyaki.getId());

        assertThat(page).as("押せてしまう").doesNotContain("この内容で注文を入れる");
        // 「できません」だけで終えると、その場で詰む。直す場所まで書く
        assertThat(page).contains("販売を再開");
        assertThat(page).contains("/kitchen/stock");
    }

    @Test
    @WithMockUser(roles = "STAFF", username = "やまだ")
    @DisplayName("★ 送信すると伝票に入り、伝票の画面へ戻る")
    void placesTheOrderAndReturnsToTheBill() throws Exception {
        mockMvc.perform(post("/hall/bills/" + bill.getId() + "/orders")
                        .with(csrf())
                        .param("itemId", String.valueOf(steak.getId()))
                        .param("quantity", "1")
                        .param("price", "6800")
                        .param("note", "ミディアムレアで"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/hall/bills/" + bill.getId()));

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getTotalAmount()).isEqualTo(6800);
        assertThat(orders.get(0).getNote()).isEqualTo("ミディアムレアで");
        // 誰が入れたかが残る（時価は人が金額を決めているため）
        assertThat(orders.get(0).getPlacedBy()).isNotBlank();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 断られたときは、選んだ品の画面に戻す（カテゴリから辿り直させない）")
    void staysOnTheItemWhenRejected() throws Exception {
        // 金額を入れずに送った場合。ここで商品選びの段まで戻されると、
        // 打ち直すためにカテゴリから辿り直すことになる
        mockMvc.perform(post("/hall/bills/" + bill.getId() + "/orders")
                        .with(csrf())
                        .param("itemId", String.valueOf(steak.getId()))
                        .param("quantity", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/hall/bills/" + bill.getId() + "/orders/new?itemId=" + steak.getId()));

        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 会計済みの伝票では画面を開かせない（選び終えてから断らない）")
    void refusesToOpenForClosedBills() throws Exception {
        tableService.closeSession(bill.getId(), false, "やまだ", null, SettlementMethod.CASH);

        mockMvc.perform(get("/hall/bills/" + bill.getId() + "/orders/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/hall/bills/" + bill.getId()));
    }

    @Test
    @DisplayName("★ ログインしていない人は入れられない")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/hall/bills/" + bill.getId() + "/orders/new"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/hall/bills/" + bill.getId() + "/orders")
                        .with(csrf())
                        .param("itemId", String.valueOf(steak.getId()))
                        .param("quantity", "1")
                        .param("price", "6800"))
                .andExpect(status().is3xxRedirection());

        assertThat(orderRepository.findAll()).isEmpty();
    }
}
