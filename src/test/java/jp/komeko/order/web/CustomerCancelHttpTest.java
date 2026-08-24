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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>お客さま自身</b>によるキャンセルを、HTTP 経由・本番と同じトランザクション境界で通すテスト。
 *
 * <p><b>このテストが守っているもの＝ {@code cancelByCustomer} の中の処理の順番</b><br>
 * {@code OrderService#cancelByCustomer} は
 * 「伝票を計算し直す（{@code refreshSessionOf}）→ 在庫を戻す（{@code restoreStockOf}）」
 * の順で書かれています。この 2 行を入れ替えると、
 * 在庫の復元が {@code @Modifying(clearAutomatically = true)} のバルク UPDATE なので
 * <b>永続化コンテキストの全エンティティがデタッチされ</b>、
 * そのあとの遅延読み込みが {@code LazyInitializationException} で落ちます。
 * お客さまのスマホから見ると、キャンセルを押した瞬間に <b>HTTP 500</b> です。
 *
 * <p><b>なぜ既存のテストでは足りないのか</b>
 * <ul>
 *   <li>{@link jp.komeko.order.service.OrderServiceIntegrationTest} は
 *       クラスに {@code @Transactional} が付いており、テストとリクエストが
 *       ひとつの永続化コンテキストを共有します。デタッチの影響が本番と変わるため、
 *       この種のバグは<b>原理的に検出できません</b>。</li>
 *   <li>{@link KitchenCancelHttpTest} は本番と同じ境界で通していますが、
 *       固定しているのは {@code /kitchen} 経由（{@code cancelByStaff}）だけです。
 *       お客さま経路は別メソッドなので、片方だけ直しても気づけません。</li>
 * </ul>
 *
 * <p>2026-08-22 に公開デモで<b>全経路</b>が 500 になった事故の、お客さま側の再発防止です。
 * だからこのクラスには {@code @Transactional} を付けません。
 * MockMvc のリクエストごとにコントローラが自前のトランザクションを張る、本番と同じ形で通します。
 *
 * <p>在庫を管理する商品を必ず 1 品含めているのも意図的です。
 * 在庫を管理していない商品だと {@code restoreStock} のバルク UPDATE 自体が走らず、
 * デタッチも起きないので、順番を入れ替えても素通りしてしまいます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("お客さま自身のキャンセル（HTTPレベル・本番と同じトランザクション境界）")
class CustomerCancelHttpTest {

    /** 注文した個数（在庫 5 から引かれて 3 になる）。 */
    private static final int ORDER_QUANTITY = 2;
    /** 在庫の初期値。 */
    private static final int INITIAL_STOCK = 5;
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

        // 「いつ走らせても同じ結果」にするため受付条件を固定する
        // （深夜料金が乗ると小計以外の金額がぶれるので 0% にしておく）
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
        // ★ 在庫を管理する商品にすること。
        //   これが null（在庫管理なし）だと restoreStock のバルク UPDATE が走らず、
        //   デタッチも起きないので、守りたいバグを踏めないテストになる
        newItem.setStockRemaining(INITIAL_STOCK);
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
    @DisplayName("POST /bill/orders/{token}/cancel が 500 にならず、在庫と伝票金額が戻る")
    void customerCancelSucceedsOverHttp() throws Exception {
        assertThat(stockOf(item)).isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        assertThat(subtotalOfBill()).isEqualTo(UNIT_PRICE * ORDER_QUANTITY);

        // お客さま画面には連番 ID ではなく推測できないトークンが出ている（bill.html）。
        // ログインは不要なので、認証を付けずに素で叩くのが本番と同じ形
        mockMvc.perform(post("/bill/orders/{token}/cancel", placed.getPublicToken())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/bill"));

        // 「拒否されずに済んだ」だけでは足りない。キャンセルの後始末が
        // 最後まで走ったことを、副作用 3 点で確かめる
        Order canceled = orderRepository.findById(placed.getId()).orElseThrow();
        assertThat(canceled.getStatus())
                .as("注文がキャンセルになっていること")
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(stockOf(item))
                .as("在庫が戻っていること")
                .isEqualTo(INITIAL_STOCK);
        assertThat(subtotalOfBill())
                .as("伝票の請求からも外れていること")
                .isZero();
    }

    private Integer stockOf(MenuItem target) {
        return menuItemRepository.findById(target.getId()).orElseThrow().getStockRemaining();
    }

    private int subtotalOfBill() {
        return tableService.getSession(bill.getId()).getSubtotalAmount();
    }
}
