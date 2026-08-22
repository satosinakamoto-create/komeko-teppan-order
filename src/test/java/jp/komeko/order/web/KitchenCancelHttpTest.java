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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 厨房画面からのキャンセルを <b>HTTP 経由・本番と同じトランザクション境界で</b> 通すテスト。
 *
 * <p><b>なぜサービス直呼びのテストとは別にこれが要るのか</b><br>
 * {@code cancelByStaff} 自体は {@link jp.komeko.order.service.StockManagementTest} が
 * 検証しているが、あちらはテストメソッドを {@code @Transactional} で包んでいる。
 * その場合、テスト全体がひとつの永続化コンテキストを共有するため、
 * <b>本番では起きる「バルク UPDATE の clearAutomatically で全エンティティが
 * デタッチされる」問題の影響が変わってしまう</b>。
 *
 * <p>実際、2026-08-22 に公開デモ（Render）でキャンセルを押すと HTTP 500 が返り、
 * サービステストは全て green のままだった。このクラスは
 * {@link CustomerFlowTest} と同じく {@code @Transactional} を付けず、
 * MockMvc のリクエストごとにコントローラが自前のトランザクションを張る
 * 本番と同じ形で、この事故を固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("厨房のキャンセル（HTTPレベル・本番と同じトランザクション境界）")
class KitchenCancelHttpTest {

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
        // （OrderServiceIntegrationTest と同じ理由。深夜料金も金額検証がぶれるので消す）
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem newItem = new MenuItem(category, "牡蠣と豚肉米粉そば", 1680);
        newItem.setCookMinutes(12);
        newItem.setStockRemaining(5);   // 在庫を管理する商品で通す（復元の経路まで踏むため）
        item = menuItemRepository.save(newItem);

        DiningTable table = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10));
        bill = tableService.openSession(table.getId(), 2);

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 2);
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
    @WithMockUser(roles = "STAFF")
    @DisplayName("POST /kitchen/orders/{id}/cancel が 500 にならず、在庫と伝票金額が戻る")
    void cancelSucceedsOverHttp() throws Exception {
        assertThat(stockOf(item)).isEqualTo(3);   // 2 個注文したので 5 → 3

        mockMvc.perform(post("/kitchen/orders/{id}/cancel", placed.getId())
                        .param("reason", "材料切れ")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // 注文はキャンセルになり、在庫が戻り、伝票の請求からも外れている
        Order canceled = orderRepository.findById(placed.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(stockOf(item)).isEqualTo(5);
        TableSession reloaded = tableService.getSession(bill.getId());
        assertThat(reloaded.getSubtotalAmount()).isZero();
    }

    private Integer stockOf(MenuItem target) {
        return menuItemRepository.findById(target.getId()).orElseThrow().getStockRemaining();
    }
}
