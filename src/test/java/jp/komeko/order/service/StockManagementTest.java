package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.*;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 残数（在庫）管理のテスト。
 *
 * <p><b>ここは金銭とお客さまの信頼に直結する</b>ので、両方向を丁寧に確かめる。
 * <ul>
 *   <li>売り越えない（残り 1 皿を 2 組に売らない・在庫不足の注文は何も変えずに断る）</li>
 *   <li>売り止めない（キャンセルで戻る・無制限の品は影響を受けない）</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockManagementTest {

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuService menuService;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private TableService tableService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private MockMvc mockMvc;

    /**
     * 在庫ルールの検証。
     *
     * <p>{@code @Transactional} をクラスに付けると、各テストの変更が
     * テスト終了時に自動で巻き戻り、テスト同士が汚し合わない。
     */
    @Nested
    @Transactional
    class 在庫ルール {

        private TableSession session;

        @BeforeEach
        void setUp() {
            // 営業時間の判定に引っかからないよう、テスト中は終日受付にしておく。
            // （実物の設定は 17:30〜翌1:30 なので、テストを昼に走らせると注文が断られてしまう）
            ShopSetting setting = shopSettingService.current();
            setting.setAcceptingOrders(true);
            setting.setOpenTime(LocalTime.MIN);
            setting.setLastOrderTime(LocalTime.of(23, 59));

            // 卓名は @Size(max=20) の制約がある。System.nanoTime() は 19 桁もあるので
            // そのまま連結すると制約違反になる（最初のテスト実行で実際に踏んだ）。
            // 下 8 桁だけ使えば、テスト内での一意性には十分。
            DiningTable table = tableService.createTable(
                    "在庫卓" + (System.nanoTime() % 100_000_000L), 4, 999);
            session = tableService.openSession(table.getId(), 2);
        }

        /** 残数つきの商品を作るヘルパー。 */
        private MenuItem itemWithStock(Integer stock) {
            Category category = categoryRepository.save(new Category("在庫テスト" + System.nanoTime(), 999));
            MenuItem item = new MenuItem(category, "限定ステーキ" + System.nanoTime(), 2000);
            item.setStockRemaining(stock);
            return menuItemRepository.save(item);
        }

        /** その商品を qty 個だけ注文するヘルパー。 */
        private Order order(MenuItem item, int qty) {
            Cart cart = new Cart();  // @SessionScope の Bean だが、テストでは直接 new して渡せる
            cartService.addToCart(cart, item.getId(), List.of(), qty);
            return orderService.placeOrder(cart, session.getId(), null);
        }

        private Integer stockOf(MenuItem item) {
            return menuService.stockRemainingOf(item.getId());
        }

        @Test
        @DisplayName("注文すると残数が減り、0 になったら注文できなくなる")
        void stockDecreasesAndSellsOut() {
            MenuItem item = itemWithStock(3);

            order(item, 2);
            assertThat(stockOf(item)).isEqualTo(1);

            order(item, 1);
            assertThat(stockOf(item)).isZero();

            // 0 になった商品は isOrderable が false になり、画面では売り切れ表示になる
            MenuItem reloaded = menuItemRepository.findById(item.getId()).orElseThrow();
            assertThat(reloaded.isOutOfStock()).isTrue();
            assertThat(reloaded.isOrderable()).isFalse();

            // さらに注文しようとするとカート追加の時点で断られる
            Cart cart = new Cart();
            assertThatThrownBy(() -> cartService.addToCart(cart, item.getId(), List.of(), 1))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("売り切れ");
        }

        @Test
        @DisplayName("残数を超える注文は断られ、在庫は 1 つも減らない")
        void overOrderIsRejectedWithoutSideEffects() {
            MenuItem limited = itemWithStock(5);
            order(limited, 3);   // 残り 2

            // 直接 placeOrder の防壁を確かめたいので、カートには成功時の状態を作ってから
            // 在庫を先に減らしておく（他の卓が先に買っていった状況の再現）
            Cart cart = new Cart();
            cartService.addToCart(cart, limited.getId(), List.of(), 2);
            menuService.tryConsumeStock(limited.getId(), 1);   // 他の卓が 1 皿確保 → 残り 1

            assertThatThrownBy(() -> orderService.placeOrder(cart, session.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("残り 1 点");

            // 断られた注文の分は 1 つも引かれていない（残り 1 のまま）
            assertThat(stockOf(limited)).isEqualTo(1);
        }

        /**
         * 「品はあるが数が足りない」ときは、勝手に減らさず注文全体を断る。
         *
         * <p>売り切れとは扱いが違います。何個にするかはお客さまが決めることなので、
         * 2 個頼まれて 1 個しか無いときに黙って 1 個にはしません。
         *
         * <p><b>このテストの前身について</b><br>
         * ここはもともと「後の品が在庫不足なら、先の品の在庫も巻き戻る」という名前でした。
         * ただし実際には巻き戻りを見ていませんでした。
         * 当時は品切れの時点で {@code refresh} が注文を差し戻していたので、
         * <b>在庫を引く処理に入る前に止まっていた</b>だけです。
         * 品切れを「落として続行」に変えたことで、その通り道が無くなりました。
         *
         * <p>先に引いた分の巻き戻しは {@code OrderService#place} の
         * {@code @Transactional} が保証しますが、<b>このテストからは確認できません</b>。
         * このクラス自身が {@code @Transactional} で、
         * {@code place} はそのトランザクションに相乗りするためです
         * （テストの中では巻き戻る前の値が見えてしまう）。
         * 確認できないものを確認したことにしないため、ここでは断ることだけを見ています。
         */
        @Test
        @DisplayName("数量が足りないときは、注文を作らずに聞き返す")
        void rejectsWhenNotEnoughStock() {
            MenuItem plenty = itemWithStock(5);
            MenuItem scarce = itemWithStock(3);

            Cart cart = new Cart();
            cartService.addToCart(cart, plenty.getId(), List.of(), 2);
            cartService.addToCart(cart, scarce.getId(), List.of(), 2);
            menuService.tryConsumeStock(scarce.getId(), 2);   // 他の卓が 2 皿確保 → 残り 1

            assertThatThrownBy(() -> orderService.placeOrder(cart, session.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("残り 1 点");

            // 足りなかった品には手を付けていない（勝手に 1 個だけ確保したりしない）
            assertThat(stockOf(scarce)).isEqualTo(1);
        }

        /**
         * 「早い者勝ちで負けた人」の通り道。
         *
         * <p>洗い替えの時点では買えたのに、ボタンを押してから残数を引くまでの間に
         * 別の卓が最後の 1 点を持っていった、という並びです。
         *
         * <p><b>以前はここで注文が丸ごと差し戻されていました。</b>
         * 4 品頼んでいたら、売り切れた 1 品のせいで残り 3 品も通りません。
         * しかもこれは誰も管理画面を触っていなくても起きます。他の卓が買っただけです。
         * 混雑時に「注文が通らない」と呼ばれる原因でした。
         *
         * <p>いまは売り切れた品だけ落として、残りは通します。
         */
        @Test
        @DisplayName("★ 押す直前に売り切れた品だけ落として、残りの注文は通る")
        void soldOutLineIsDroppedAndTheRestGoesThrough() {
            MenuItem plenty = itemWithStock(5);
            MenuItem scarce = itemWithStock(1);

            Cart cart = new Cart();
            cartService.addToCart(cart, plenty.getId(), List.of(), 2);
            cartService.addToCart(cart, scarce.getId(), List.of(), 1);
            menuService.tryConsumeStock(scarce.getId(), 1);   // 他の卓が最後の 1 皿を確保

            OrderService.Placed placed = orderService.place(cart, session.getId(), null);

            // 通った品は注文になっている
            assertThat(placed.order().getLines())
                    .as("売り切れた品を除いた 1 行だけが注文になる")
                    .hasSize(1);
            assertThat(placed.order().getLines().get(0).getMenuItemId()).isEqualTo(plenty.getId());
            assertThat(stockOf(plenty)).isEqualTo(3);

            // 落とした品は、お客さまに伝えるための案内として返る
            assertThat(placed.soldOutNotices())
                    .as("何が落ちたかを画面で伝えられること")
                    .hasSize(1)
                    .first().asString().contains(scarce.getName()).contains("売り切れ");
        }

        @Test
        @DisplayName("全部売り切れていたら、これまでどおり断る")
        void allSoldOutIsStillRejected() {
            MenuItem scarce = itemWithStock(1);

            Cart cart = new Cart();
            cartService.addToCart(cart, scarce.getId(), List.of(), 1);
            menuService.tryConsumeStock(scarce.getId(), 1);

            // 1 品も通らないのに「承りました」と出しては嘘になる
            assertThatThrownBy(() -> orderService.placeOrder(cart, session.getId(), null))
                    .isInstanceOf(OrderRejectedException.class)
                    .hasMessageContaining("売り切れ");
        }

        @Test
        @DisplayName("キャンセルすると残数が戻る。二重キャンセルでも二重には戻らない")
        void cancelRestoresStockExactlyOnce() {
            MenuItem item = itemWithStock(5);
            Order placed = order(item, 2);
            assertThat(stockOf(item)).isEqualTo(3);

            orderService.cancelByStaff(placed.getId(), "テスト", "テスト担当");
            assertThat(stockOf(item)).isEqualTo(5);

            // もう一度キャンセルしても（すでにキャンセル済みなので）在庫は増えない。
            // 増えてしまうと、実在しない在庫が画面に出て売り越えの原因になる。
            orderService.cancelByStaff(placed.getId(), "テスト2", "テスト担当");
            assertThat(stockOf(item)).isEqualTo(5);
        }

        @Test
        @DisplayName("在庫を管理しない商品（null）は何個注文しても影響を受けない")
        void untrackedItemsAreUnlimited() {
            MenuItem drink = itemWithStock(null);

            order(drink, 10);
            order(drink, 10);

            assertThat(stockOf(drink)).isNull();
            assertThat(menuItemRepository.findById(drink.getId()).orElseThrow().isOrderable()).isTrue();
        }

        @Test
        @DisplayName("残数の設定と解除（厨房パネルの操作）が反映される")
        void setAndClearStock() {
            MenuItem item = itemWithStock(null);

            menuService.setStock(item.getId(), 7);
            assertThat(stockOf(item)).isEqualTo(7);

            menuService.setStock(item.getId(), null);
            assertThat(stockOf(item)).isNull();

            assertThatThrownBy(() -> menuService.setStock(item.getId(), -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 画面表示の検証（テンプレートの式が実在の getter を指しているかの確認を兼ねる）。
     *
     * <p>ここにも {@code @Transactional} を付けて、作ったデータを必ず巻き戻す。
     * MockMvc は同じスレッドで動くので、リクエスト処理もテストのトランザクションに
     * 参加し、最後にまとめてロールバックされる。
     *
     * <p>最初はこれを付け忘れて、開いた伝票が共有のテスト DB にコミットされたまま残り、
     * <b>別のテストクラス（伝票の件数を検証しているもの）を壊した</b>。
     * 「自分は通るが他人を落とすテスト」はテスト同士の実行順に依存する
     * いちばん厄介なバグになるので、後始末は必ずロールバックに任せること。
     */
    @Nested
    @Transactional
    class 画面表示 {

        @Test
        @DisplayName("卓についたお客さまのメニューに「残り N 点」が表示される")
        void menuShowsRemainingCount() throws Exception {
            Category category = categoryRepository.save(new Category("画面テスト" + System.nanoTime(), 998));
            MenuItem item = new MenuItem(category, "画面テスト限定品" + System.nanoTime(), 1500);
            item.setStockRemaining(3);
            menuItemRepository.save(item);

            // 卓名は 20 文字制限があるので短い一意名にする（在庫ルール側の setUp と同じ理由）
            DiningTable table = tableService.createTable(
                    "画面卓" + (System.nanoTime() % 100_000_000L), 2, 998);

            // 同じブラウザセッションを使い回すため MockHttpSession を共有する
            org.springframework.mock.web.MockHttpSession httpSession =
                    new org.springframework.mock.web.MockHttpSession();

            // 卓の QR を読んで人数を確定（伝票が開く）
            mockMvc.perform(post("/t/{token}/start", table.getAccessToken())
                            .param("guestCount", "2")
                            .session(httpSession)
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());

            // メニューに「残り 3 点」のバッジが出ている
            mockMvc.perform(get("/").session(httpSession))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("残り 3 点")));
        }
    }
}
