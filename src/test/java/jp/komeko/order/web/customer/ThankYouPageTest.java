package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.SettlementMethod;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.CartService;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * 「ご来店ありがとうございました」（会計後の画面）のテスト。
 *
 * <p><b>守っているのは 2 つです。</b>
 *
 * <p>1 つは<b>金額が手元に残ること</b>。
 * 締めた瞬間にお客さまの画面から明細も合計も消えると、
 * 割り勘の計算も、経費で落とすときの控えも取れません。
 *
 * <p>もう 1 つは<b>他人の伝票を見せないこと</b>。こちらが本丸です。
 * 「その卓の最後に締まった伝票」を出す作りにすると、
 * 同じ席に次の組が入って会計まで済ませたとき、
 * 前の組のスマホに<b>次の組の伝票</b>が出ます。
 * 何を頼んでいくら払ったかは、他人に見せてよいものではありません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ご来店ありがとうございました（会計後）")
class ThankYouPageTest {

    private static final int SOBA = 1180;

    @Autowired
    private MockMvc mockMvc;
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
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    private DiningTable table;
    private MenuItem soba;

    @BeforeEach
    void setUp() {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("粉もの", 10));
        soba = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", SOBA));
        table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
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

    /** QR を読んで人数を決め、その卓についたブラウザを作る。 */
    private MockHttpSession seat(int guests) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(s));
        mockMvc.perform(post("/t/" + table.getAccessToken() + "/start").session(s).with(csrf())
                .param("guestCount", String.valueOf(guests)));
        return s;
    }

    private void order(int quantity) {
        TableSession bill = tableService.currentSession(table.getId()).orElseThrow();
        Cart cart = new Cart();
        cartService.addToCart(cart, soba.getId(), List.of(), quantity);
        orderService.placeOrder(cart, bill.getId(), null);
    }

    private String bill(MockHttpSession s) throws Exception {
        return mockMvc.perform(get("/bill").session(s))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★ 会計後も、自分が払った明細と合計が残る")
    void keepsTheBillAfterCheckout() throws Exception {
        MockHttpSession s = seat(2);
        order(2);
        TableSession mine = tableService.currentSession(table.getId()).orElseThrow();
        tableService.closeSession(mine.getId(), false, "店長", null, SettlementMethod.CASH);

        String page = bill(s);

        assertThat(page).as("お礼").contains("ご来店ありがとうございました");
        assertThat(page).as("頼んだ品").contains("肉玉米粉そば");
        assertThat(page).as("明細の金額").contains("¥2,360");
        assertThat(page).as("テーブルチャージ").contains("¥900");
        assertThat(page).as("合計（2360 + 900）").contains("¥3,260");
        assertThat(page).as("お支払い方法").contains("現金");
    }

    @Test
    @DisplayName("★ 同じ席の次の組の伝票は、絶対に出さない")
    void neverShowsSomeoneElsesBill() throws Exception {
        // 1 組目：頼んで、会計まで済ませる
        MockHttpSession first = seat(2);
        order(2);
        TableSession firstBill = tableService.currentSession(table.getId()).orElseThrow();
        tableService.closeSession(firstBill.getId(), false, "店長", null, SettlementMethod.CASH);

        // 片付け完了を挟む（2026-09-07 の 4 状態目）。
        // 会計した卓は片付け待ちになり、そのままでは次の組をご案内できない。
        // このテストの本題（他人の伝票を見せない）はその先の話なので、
        // 現実の手順どおりスタッフが片付けてから 2 組目を通す
        tableService.markCleaned(table.getId());

        // 2 組目：同じ席に入り、別の金額で会計する
        MockHttpSession second = seat(4);
        order(1);
        TableSession secondBill = tableService.currentSession(table.getId()).orElseThrow();
        tableService.closeSession(secondBill.getId(), false, "店長", null, SettlementMethod.CARD);

        // ★ 1 組目のスマホに出るのは、1 組目の伝票でなければならない
        String page = bill(first);
        assertThat(page).as("1 組目の合計（2360 + 900）").contains("¥3,260");
        assertThat(page)
                .as("2 組目の合計（1180 + 1800）が漏れている")
                .doesNotContain("¥2,980");
        assertThat(page)
                .as("2 組目の支払い方法が漏れている")
                .doesNotContain("カード");
    }

    @Test
    @DisplayName("伝票が分からないときは、金額を作らずお礼だけ出す")
    void showsOnlyThanksWhenTheBillIsUnknown() throws Exception {
        // QR を読んだだけで、伝票につかないまま来たブラウザ
        MockHttpSession s = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(s));

        String page = bill(s);

        assertThat(page).as("お礼は出す").contains("ご来店ありがとうございました");
        // 分からないものを、それらしく埋めない。
        //
        // ★ 「お会計の内容」という文字では見分けられない。
        //   いちばん下の案内文（「お会計の内容についてのお問い合わせは…」）にも
        //   同じ言葉が入っていて、伝票が無くても必ず出る。
        //   伝票の塊にしか無い合計行の印で見る。
        assertThat(page).as("合計を作ってしまっている").doesNotContain("bill-total");
    }

    @Test
    @DisplayName("会計前は、いつもの伝票のまま")
    void openBillIsUnchanged() throws Exception {
        MockHttpSession s = seat(2);
        order(1);

        String page = bill(s);

        assertThat(page).as("伝票の見出し").contains("お席の伝票");
        assertThat(page).as("会計後の画面が出てしまっている")
                .doesNotContain("ご来店ありがとうございました");
    }
}
