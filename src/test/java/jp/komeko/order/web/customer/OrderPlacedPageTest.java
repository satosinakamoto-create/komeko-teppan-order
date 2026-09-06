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
    @DisplayName("★ チェックが線で引かれる（長さの指定が 2 箇所に散らない）")
    void drawsTheCheckMark() throws Exception {
        Order now = place(soba, 1);

        String page = html("/ordered/" + now.getPublicToken());

        // 線を引く動きは、SVG 側の pathLength と CSS 側の dasharray が
        // 対になって初めて成立する。
        // pathLength="1" が「この線の全長は 1」と宣言しているので、
        // CSS は実寸（約 38.3）を書き写さずに済む。
        // ここが落ちると、CSS 側の 1 が「1 単位ぶんの点線」の意味になり、
        // チェックが細切れの点線として出る
        assertThat(page).as("線の長さを 1 として宣言していない").contains("pathLength=\"1\"");

        String css = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).as("線を引く動きが無い").contains("@keyframes placed-check-draw");
        assertThat(css).as("チェックに動きを割り当てていない").contains("placed-check-draw 520ms");
        // 飾りの動きなので、控えめにしたい人には出したままにする。
        // animation: none だけだと stroke-dashoffset: 1 が残って
        // チェックが消えたままになる。0 に戻すところまでが対。
        //
        // 探す範囲をこの動きの前後に絞るのが要点。app.css には
        // 別の目的の prefers-reduced-motion が先にあるので、
        // ファイル全体で最初の 1 件を見ると、無関係な指定を読んで受かってしまう
        int anim = css.indexOf("@keyframes placed-check-draw");
        String region = css.substring(anim, Math.min(css.length(), anim + 1600));
        assertThat(region).as("動きの設定を尊重していない").contains("prefers-reduced-motion");
        assertThat(region).as("動きを止めたときにチェックが消えたままになる")
                .contains("stroke-dashoffset: 0");
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
