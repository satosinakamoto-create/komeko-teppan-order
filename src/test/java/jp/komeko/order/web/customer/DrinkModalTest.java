package jp.komeko.order.web.customer;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OptionChoice;
import jp.komeko.order.domain.OptionGroup;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
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

/**
 * 飲み物を選ぶ画面（設計 暗08 ドリンクの濃さを選ぶ）。
 *
 * <p><b>なぜ粉ものと作りを変えるのか</b><br>
 * 粉もの（暗05）は写真を 213px の帯で出しますが、
 * 飲み物はそれを 72px の見出し行に収めます。
 * 飲み物は品数が多く写真映えの差も小さいので、
 * 大きく出すより濃さや数量を早く出したほうが用が済みます。
 * 一覧を行で並べているのと同じ判断です。
 *
 * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
 * 本番と同じ形で描画させるためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("飲み物のモーダル（暗08）")
class DrinkModalTest {

    @Autowired
    private MockMvc mockMvc;
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

    private MockHttpSession browser;
    private MenuItem highball;
    private MenuItem okonomiyaki;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        shopSettingService.save(setting);

        // タブ名が「ドリンク」かどうかで見せ方が変わる。
        // カテゴリ名ではなくタブ名で判定しているので、そこを再現する
        Category whisky = new Category("ウィスキー", 100);
        whisky.setGroupName("ドリンク");
        whisky = categoryRepository.save(whisky);

        MenuItem hb = new MenuItem(whisky, "角ハイボール", 680);
        hb.setImagePath("/images/menu/highball.jpg");
        hb.setDescription("サントリー角");
        OptionGroup strength = new OptionGroup("濃さ", 1, 1, 10);
        strength.addChoice(new OptionChoice("濃いめ", 0, 10));
        OptionChoice normal = new OptionChoice("ふつう", 0, 20);
        normal.setDefaultSelected(true);
        strength.addChoice(normal);
        strength.addChoice(new OptionChoice("薄め", 0, 30));
        hb.addOptionGroup(strength);
        highball = menuItemRepository.save(hb);

        Category konamono = new Category("広島風お好み焼き", 10);
        konamono.setGroupName("お食事");
        konamono = categoryRepository.save(konamono);
        MenuItem ok = new MenuItem(konamono, "肉玉米粉そば", 1180);
        ok.setImagePath("/images/menu/okonomi.jpg");
        okonomiyaki = menuItemRepository.save(ok);

        DiningTable table = diningTableRepository.save(new DiningTable("カウンター札1", 6, 10));
        browser = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(browser));
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

    private String page(MenuItem item) throws Exception {
        return mockMvc.perform(get("/items/" + item.getId()).session(browser))
                .andReturn().getResponse().getContentAsString();
    }

    // ========================================================================

    @Test
    @DisplayName("★ 濃さが選べる（これが無いと設計の画面が空で開く）")
    void offersTheStrengthChoices() throws Exception {
        String html = page(highball);

        assertThat(html).contains("濃さ");
        assertThat(html).contains("濃いめ").contains("ふつう").contains("薄め");
        // 必ず 1 つ選ぶ組なのでラジオ。既定が入っていれば、そのまま押しても通る
        assertThat(html).as("ラジオになっていない").contains("type=\"radio\"");
    }

    @Test
    @DisplayName("★ 飲み物は 72px の見出し行（大きい写真の帯は出さない）")
    void drinkUsesTheCompactHeader() throws Exception {
        String html = page(highball);

        assertThat(html).as("見出し行が無い").contains("drink-head");
        assertThat(html).as("飲み物に大きい写真の帯が出ている").doesNotContain("class=\"item-photo\"");
        // 価格は見出しの中。ここが th:replace だと span ごと差し替わって
        // .drink-head__price（18px・きつね色）が消える
        assertThat(html).as("見出しの中に価格が無い").contains("drink-head__price");
    }

    @Test
    @DisplayName("★ 粉ものは今までどおり大きい写真（飲み物の形にしない）")
    void foodKeepsTheLargePhoto() throws Exception {
        String html = page(okonomiyaki);

        assertThat(html).as("粉ものが飲み物の形になっている").doesNotContain("drink-head");
        assertThat(html).as("大きい写真が消えている").contains("class=\"item-photo\"");
    }

    @Test
    @DisplayName("飲み物には「焼き上がりの目安」を出さない")
    void drinkHidesTheCookTime() throws Exception {
        assertThat(page(highball)).doesNotContain("焼き上がりの目安");
    }
}
