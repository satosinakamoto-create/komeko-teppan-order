package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「ご注文を承りました」の画面（設計 暗07）のテスト。
 *
 * <p><b>この画面が守っているもの＝頼み間違いに、その場で気づけること</b><br>
 * 以前は注文のあと伝票へ飛ばしていました。そこには<b>これまでの注文が全部</b>並びます。
 * 3 杯目のビールを頼んだ人の画面にはビールが 3 行。
 * どれがいま頼んだぶんか分かりません。
 *
 * <p>気づけるのは注文した直後がいちばん早く、そこを逃すと
 * 品が出てきてから「頼んでいない」になります。
 * だからこの画面は<b>いま通った注文だけ</b>を出します。
 * ここに他の注文が混ざったら、この画面の存在意義が無くなります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ご注文を承りました（暗07）")
class OrderPlacedPageTest {

    private static final int SOBA = 1180;
    private static final int BEER = 580;

    @Autowired
    private MockMvc mockMvc;
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
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    private TableSession bill;
    private MenuItem soba;
    private MenuItem beer;

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
        beer = menuItemRepository.save(new MenuItem(category, "生ビール（中）", BEER));

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        bill = tableService.openSession(table.getId(), 2);
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

    private Order place(MenuItem item, int quantity) {
        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), quantity);
        return orderService.placeOrder(cart, bill.getId(), null);
    }

    private String html(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★ いま通った注文だけが出る（前の注文は混ざらない）")
    void showsOnlyTheOrderJustPlaced() throws Exception {
        place(soba, 1);                       // さっき頼んだぶん
        Order now = place(beer, 2);           // いま頼んだぶん

        String page = html("/ordered/" + now.getPublicToken());

        assertThat(page).as("見出し").contains("ご注文を承りました");
        assertThat(page).as("いま頼んだ品").contains("生ビール（中）");
        assertThat(page).as("数量").contains("×2");
        assertThat(page).as("金額").contains("¥1,160");
        // ★ ここが本丸。前の注文が並ぶなら、この画面を分けた意味が無い
        assertThat(page)
                .as("前の注文が混ざっている")
                .doesNotContain("肉玉米粉そば");
    }

    @Test
    @DisplayName("次にすることは「メニューに戻る」だけ")
    void offersOnlyOneNextStep() throws Exception {
        Order now = place(soba, 1);

        String page = html("/ordered/" + now.getPublicToken());

        assertThat(page).as("お待ちいただく案内").contains("お席でお待ちください");
        assertThat(page).as("戻る先").contains("メニューに戻る");
    }

    @Test
    @DisplayName("再読み込みしても同じ画面が出る（PRG）")
    void isReloadable() throws Exception {
        Order now = place(soba, 1);
        String token = now.getPublicToken();

        assertThat(html("/ordered/" + token)).contains("ご注文を承りました");
        assertThat(html("/ordered/" + token)).contains("ご注文を承りました");
    }

    @Test
    @DisplayName("知らないトークンでは伝票に戻す（エラー画面を出さない）")
    void unknownTokenFallsBackToTheBill() throws Exception {
        // 古い URL をブックマークから開いた、など。
        // お客さまにとって「さっき頼んだもの」は伝票にあるはずなので、そちらへ送る
        mockMvc.perform(get("/ordered/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ログインしていなくても開ける（お客さまの画面なので）")
    void isPublic() throws Exception {
        Order now = place(soba, 1);

        // ここが 302（ログインへ）になると、注文した直後に
        // ログイン画面が出るという最悪の体験になる。
        // SecurityConfig の permitAll に /ordered/** を足してあることの確認
        mockMvc.perform(get("/ordered/" + now.getPublicToken()))
                .andExpect(status().isOk());
    }
}
