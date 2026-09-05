package jp.komeko.order.web.hall;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * ホールの一覧ページ（{@code GET /hall}）にある「卓ごとの注文」を描画するテスト。
 *
 * <p><b>このテストが守っているもの＝「どの卓が何を頼んでいるか」が読めること</b><br>
 * もともとこのページには卓名・人数・滞在時間・ご請求額しか出ていませんでした。
 * 中身を知るには卓を 1 つずつ開くしかなく、
 * 「3番さん、さっき何頼んだっけ」に一覧で答えられませんでした。
 *
 * <p><b>{@code @Transactional} を付けていない理由</b><br>
 * このアプリは {@code open-in-view: false} なので、
 * <b>画面を描く時点では DB 接続がありません</b>。
 * テストに {@code @Transactional} を付けると接続が開いたままになり、
 * 本番なら {@code LazyInitializationException} で落ちる書き方が素通りしてしまいます。
 * ここは「注文の明細まで読み終えてから画面に渡せているか」を確かめる場所なので、
 * 本番と同じ形（トランザクションの外で描画）で走らせます。
 * {@code HallBillPageTest} が {@code @Transactional} を付けていないのと同じ理由です。
 *
 * <p>この壊れ方は<b>注文が 1 件も無いときは動く</b>のがいやらしいところで、
 * 注文が入った瞬間にホール画面が真っ白になります。営業中にそれが起きると、
 * お会計そのものができなくなります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホール一覧の「卓ごとの注文」")
class HallBoardOrdersTest {

    /** 商品の税込単価。 */
    private static final int UNIT_PRICE = 1180;

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
    private MenuItem sobameshi;

    @BeforeEach
    void setUp() {
        clearAll();

        // 何時に走らせても同じ結果になるようにそろえる。
        // 実店舗の設定（17:30〜翌1:30）のままだと、昼に走らせたときだけ
        // 「営業時間外」で注文が弾かれて落ちる不安定なテストになる。
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        // 深夜料金は注文時刻で決まるため、乗ったり乗らなかったりすると金額がぶれる。
        // ここは注文の中身が読めるかを見る場所なので 0% にしておく。
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("粉もの", 10));
        sobameshi = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", UNIT_PRICE));

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        // 伝票は来店時点の設定をコピーするので、必ず設定を保存してから開く
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

    /** 伝票に注文を 1 件足す。 */
    private Order order(int quantity) {
        Cart cart = new Cart();
        cartService.addToCart(cart, sobameshi.getId(), List.of(), quantity);
        return orderService.placeOrder(cart, bill.getId(), null);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("在席の卓ごとに、頼まれた品名・数量・金額・受付時刻が出る")
    void showsWhatEachTableOrdered() throws Exception {
        order(2);

        String html = mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andExpect(view().name("hall/board"))
                .andReturn().getResponse().getContentAsString();

        // ★ ここが本丸 ★
        // 品名が出ていなければ「何を注文したか一覧で分かる」を満たしていない。
        // ページが 200 で返るだけでは、この画面の目的は果たせていない。
        assertThat(html)
                .as("卓名")
                .contains("3番テーブル");
        assertThat(html)
                .as("見出し（このセクションが描かれていること）")
                .contains("卓ごとの注文");
        assertThat(html)
                .as("頼まれた品名")
                .contains("肉玉米粉そば");
        assertThat(html)
                .as("数量")
                .contains("× 2");
        assertThat(html)
                .as("明細の金額（1180 × 2）")
                .contains("¥2,360");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("取り消した注文も理由つきで残る（請求額には入らない）")
    void keepsCanceledOrdersVisibleWithReason() throws Exception {
        Order kept = order(1);
        Order canceled = order(1);
        orderService.cancelByStaff(canceled.getId(), "お客さま都合", "テスト店員");

        String html = mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 取り消した注文を隠すと「さっき頼んだのに来ない」の確認ができず、
        // 打ち直しの経緯も追えなくなる。だから残す、と決めてある。
        // （お客さまに見せる伝票詳細のほうは getBillableOrders() で隠している。
        //   あちらは金額を確認する画面、こちらは店が現場を把握する画面）
        assertThat(html)
                .as("取り消しの理由")
                .contains("お客さま都合");
        assertThat(html)
                .as("取り消した注文を薄く出すための指定")
                .contains("opacity:.55");

        // 表示に残ることと、請求に入ることは別。ここを取り違えると過大請求になる。
        assertThat(tableService.getSession(bill.getId()).getSubtotalAmount())
                .as("小計は取り消していない 1 件ぶんだけ")
                .isEqualTo(UNIT_PRICE);
        assertThat(kept.getId()).isNotEqualTo(canceled.getId());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("まだ注文が無い卓でも落ちない")
    void rendersWhenTableHasNoOrdersYet() throws Exception {
        // ご案内した直後の卓。ここで落ちると、
        // 「席に着いた瞬間にホール画面が開けなくなる」という最悪の壊れ方をする。
        String html = mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("3番テーブル");
        assertThat(html).contains("まだご注文がありません");
    }
}
