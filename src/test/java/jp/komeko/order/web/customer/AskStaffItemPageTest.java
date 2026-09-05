package jp.komeko.order.web.customer;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.ShopSetting;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 時価・おまかせの品の画面（設計 暗21 / 暗22）のテスト。
 *
 * <p><b>守っているのは「いくらか分からないまま頼めてしまわないこと」です。</b><br>
 * 価格が決まっていない品に、ほかの品と同じ「注文に追加 ¥880」の形の
 * ボタンが並ぶと、お客さまは金額を確かめずに押せます。
 * 出てきてから値段を聞くことになり、断りにくい。
 *
 * <p>設計はこれを、<b>主な操作を「スタッフを呼ぶ」にする</b>ことで解いています。
 * 注文に追加は残しますが、枠線の控えめなボタンに落としてあります。
 * ボタンの強さが順番（聞いてから決める）を表しているので、
 * ここが入れ替わると設計の意図ごと失われます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("時価・おまかせの品（暗21 / 暗22）")
class AskStaffItemPageTest {

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

    /** 時価の品（価格が決まっていない）。 */
    private MenuItem sirloin;
    /** 値段のある品。こちらは今までどおりの画面のまま、を確かめるため。 */
    private MenuItem soba;

    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("数量限定", 10));
        // 価格 0 ＝ 時価。売り切れにはしていない（押せる状態も見たいので）
        sirloin = menuItemRepository.save(
                new MenuItem(category, "国産牛サーロインステーキ", 0));
        sirloin.setDescription("仕入れ状況により価格が変わります。");
        menuItemRepository.save(sirloin);

        soba = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", 1180));

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        tableService.openSession(table.getId(), 2);

        // 卓に着いた状態を作る（QR を読んだのと同じ）
        session = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(session));
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

    private String html(MenuItem item) throws Exception {
        return mockMvc.perform(get("/items/" + item.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★ 主な操作は「スタッフを呼ぶ」で、注文に追加は控えめなボタン")
    void staffCallIsThePrimaryAction() throws Exception {
        String page = html(sirloin);

        // きつね色の主要ボタンは「スタッフを呼ぶ」のほう
        assertThat(page).as("主要ボタン")
                .contains("btn btn--primary btn--lg btn--block\">スタッフを呼ぶ");
        // 注文に追加は枠線。ここが主要になると、値段を聞く前に押させることになる
        assertThat(page).as("注文に追加は枠線").contains("btn btn--outline");
        assertThat(page).contains("注文に追加");
        assertThat(page).as("やめる").contains("やめる");
    }

    @Test
    @DisplayName("★ 金額は「—」。0 円や「時価」を金額の欄に置かない")
    void amountIsShownAsUndecided() throws Exception {
        String page = html(sirloin);

        assertThat(page).as("金額の欄").contains("askstaff__amount");
        assertThat(page).as("未定を表す —").contains("—");
        // 「¥0」と出ると、0 円で頼めるように読める
        assertThat(page).as("0 円と書いてしまっている").doesNotContain("¥0");
    }

    @Test
    @DisplayName("説明と注意書きが出る")
    void showsDescriptionAndNotice() throws Exception {
        String page = html(sirloin);

        assertThat(page).contains("国産牛サーロインステーキ");
        assertThat(page).contains("仕入れ状況により価格が変わります。");
        assertThat(page).as("注意書きの枠").contains("askstaff__notice");
        assertThat(page).contains("スタッフがお席でご説明します。");
    }

    @Test
    @DisplayName("★ 値段のある品は、今までどおりの注文フォームのまま")
    void pricedItemsAreUnchanged() throws Exception {
        String page = html(soba);

        // 時価用の画面が出てはいけない
        assertThat(page).as("値段のある品に時価の画面が出ている")
                .doesNotContain("askstaff__notice");
        // ふつうの注文フォームと金額が出ていること
        assertThat(page).contains("id=\"add-form\"");
        assertThat(page).contains("¥1,180");
    }

    @Test
    @DisplayName("時価の品に、金額入りの注文フォームは出さない（2 つ並べない）")
    void doesNotAlsoRenderThePricedForm() throws Exception {
        String page = html(sirloin);

        // 両方出ると「注文に追加」が 2 つ並び、片方だけ金額が付く
        assertThat(page).as("ふつうの注文フォームまで出ている")
                .doesNotContain("id=\"add-form\"");
    }
}
