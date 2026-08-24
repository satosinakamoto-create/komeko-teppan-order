package jp.komeko.order.web;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「状態を進める」口（{@code POST /kitchen/orders/{id}/status}）から
 * キャンセルを実行できないことを固定するテスト。
 *
 * <p><b>このテストが守っているもの＝キャンセルの入口をひとつに保つこと</b><br>
 * 公開デモでは、見学者（{@code ROLE_GUEST}）にキャンセルを許していません。
 * 伝票から品が消える＝次に見に来た人の画面に残る変更だからです。
 * ところが {@code SecurityConfig} が閉じていたのは
 * {@code POST /kitchen/orders/*&#47;cancel} だけで、状態変更の口へ
 * {@code status=CANCELED} を送ると<b>まったく同じ結果になりました</b>
 * （状態が CANCELED になり、在庫が戻り、伝票の小計が消える）。
 * 画面のボタンは {@code board.html} が描き分けているだけなので、
 * URL を直接叩けば素通りします（2026-08-24 に実測で再現）。
 *
 * <p>塞ぎ方は「見学者には許さない」ではなく
 * <b>「この口はキャンセルという操作をしない」</b>です。
 * キャンセルには専用の {@code /cancel} があり、画面もそちらへ送るので、
 * 実スタッフを含め正規の利用者は誰も影響を受けません。
 * ロールで切ると、あとからロールが増えるたびに条件を足して回ることになります。
 *
 * <p><b>なぜ「拒否された」だけでなく在庫と伝票まで見るのか</b><br>
 * この抜け道の怖いところは<b>成功メッセージまで出ていた</b>ことです。
 * 応答の形だけを見ていると「拒否したつもりで実は通っている」に気づけません。
 * 副作用（在庫の復元・小計の消失）が 1 つも起きていないことまで確かめます。
 *
 * <p>{@code @Transactional} を付けないのは {@link KitchenCancelHttpTest} と同じ理由で、
 * リクエストごとにコントローラが自前のトランザクションを張る本番と同じ形で通すためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.guest-login=true")   // 抜け道が問題になるのは公開デモの設定
@DisplayName("状態変更の口からのキャンセル迂回")
class KitchenStatusCancelBypassTest {

    /** 注文した個数（在庫 5 から引かれて 3 になる）。 */
    private static final int ORDER_QUANTITY = 2;
    /** 商品の税込単価。 */
    private static final int UNIT_PRICE = 1680;

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

    private MenuItem item;
    private TableSession bill;
    private Order placed;

    @BeforeEach
    void setUp() {
        clearAll();

        // 「いつ走らせても同じ結果」にするため受付条件を固定する。
        // 深夜料金が乗ると小計以外の金額がぶれるので 0% にしておく。
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem newItem = new MenuItem(category, "牡蠣と豚肉米粉そば", UNIT_PRICE);
        newItem.setCookMinutes(12);
        // 在庫を管理する商品にしておく。キャンセルが通ってしまうと在庫が戻るので、
        // 「通ってしまった」ことをこの数字で検出できる
        newItem.setStockRemaining(5);
        item = menuItemRepository.save(newItem);

        DiningTable table = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10));
        bill = tableService.openSession(table.getId(), 2);

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), ORDER_QUANTITY);
        placed = orderService.placeOrder(cart, bill.getId(), null);
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

    @Test
    @WithMockUser(roles = {"STAFF", "GUEST"})
    @DisplayName("見学者が status=CANCELED を送っても、キャンセルされない")
    void guestCannotCancelThroughStatusEndpoint() throws Exception {
        assertThat(stockOf(item)).isEqualTo(3);                       // 2 個注文したので 5 → 3
        assertThat(subtotalOfBill()).isEqualTo(UNIT_PRICE * ORDER_QUANTITY);

        mockMvc.perform(post("/kitchen/orders/{id}/status", placed.getId())
                        .with(csrf())
                        .param("status", "CANCELED"))
                // 認可ではなく入力の妥当性で弾いているので、403 ではなくボードへ戻る。
                // 見ているのは「キャンセルされないこと」であって、状態コードの数字ではない
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashErrors"))
                // 成功メッセージが出ていた（＝実際に通っていた）のが、この不具合の本体
                .andExpect(flash().attribute("flashSuccess", org.hamcrest.Matchers.nullValue()));

        assertRejected();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("実スタッフが status=CANCELED を送っても、キャンセルされない（ロールの話ではない）")
    void staffCannotCancelThroughStatusEndpointEither() throws Exception {
        // 「見学者だから閉じている」のではなく「この口がその操作をしない」。
        // ここが 403 やロール判定に化けていないことを、実スタッフでも確かめておく。
        // 実スタッフのキャンセルは専用の /cancel が受け持ち、そちらは
        // KitchenCancelHttpTest が通ることを保証している
        mockMvc.perform(post("/kitchen/orders/{id}/status", placed.getId())
                        .with(csrf())
                        .param("status", "CANCELED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashErrors"));

        assertRejected();
    }

    @Test
    @WithMockUser(roles = {"STAFF", "GUEST"})
    @DisplayName("見学者の「焼きはじめ」は、これまでどおり通る")
    void guestCanStillAdvanceStatus() throws Exception {
        // 公開デモの見せ場（状態を進めるとお客さまの画面が変わる）を潰していないこと。
        // キャンセルを弾く条件が広すぎると、ここが最初に壊れる
        mockMvc.perform(post("/kitchen/orders/{id}/status", placed.getId())
                        .with(csrf())
                        .param("status", "COOKING"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(reloadOrder().getStatus()).isEqualTo(OrderStatus.COOKING);
        // 状態が進んだだけで、在庫も請求も動かない
        assertThat(stockOf(item)).isEqualTo(3);
        assertThat(subtotalOfBill()).isEqualTo(UNIT_PRICE * ORDER_QUANTITY);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("実スタッフの「焼きはじめ」も、これまでどおり通る")
    void realStaffCanStillAdvanceStatus() throws Exception {
        mockMvc.perform(post("/kitchen/orders/{id}/status", placed.getId())
                        .with(csrf())
                        .param("status", "COOKING"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        assertThat(reloadOrder().getStatus()).isEqualTo(OrderStatus.COOKING);
        assertThat(stockOf(item)).isEqualTo(3);
        assertThat(subtotalOfBill()).isEqualTo(UNIT_PRICE * ORDER_QUANTITY);
    }

    /**
     * キャンセルが「形だけでなく本当に」拒否されたことを確かめる。
     *
     * <p>状態・在庫・伝票の 3 つとも、注文直後のまま動いていないこと。
     * 1 つでも動いていたら、キャンセルの経路が途中まで走っている。
     */
    private void assertRejected() {
        assertThat(reloadOrder().getStatus())
                .as("注文の状態が変わっていないこと")
                .isEqualTo(OrderStatus.RECEIVED);
        assertThat(stockOf(item))
                .as("在庫が復元されていないこと（キャンセルが走ると 5 に戻る）")
                .isEqualTo(3);
        assertThat(subtotalOfBill())
                .as("伝票の小計が消えていないこと（キャンセルが走ると 0 になる）")
                .isEqualTo(UNIT_PRICE * ORDER_QUANTITY);
    }

    private Order reloadOrder() {
        return orderRepository.findById(placed.getId()).orElseThrow();
    }

    private Integer stockOf(MenuItem target) {
        return menuItemRepository.findById(target.getId()).orElseThrow().getStockRemaining();
    }

    private int subtotalOfBill() {
        return tableService.getSession(bill.getId()).getSubtotalAmount();
    }
}
