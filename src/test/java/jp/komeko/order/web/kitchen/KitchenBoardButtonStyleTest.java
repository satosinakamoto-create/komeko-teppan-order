package jp.komeko.order.web.kitchen;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 厨房ボードの<b>ボタンの強弱</b>を、描き出された HTML で固定するテスト。
 *
 * <p><b>このテストが守っているもの＝「自然な次の一手が、いちばん目立つ」</b><br>
 * 2026-08-26 まで、ボタンの見た目は<b>遷移先の状態名だけ</b>で決まっていました。
 *
 * <pre>
 *   th:classappend="${next.name() == 'COMPLETED'} ? 'btn--primary btn--lg'
 *                   : (${next.name() == 'READY'} ? 'btn--ok' : '')"
 * </pre>
 *
 * <p>その結果、受付レーンだけ話が逆になっていました。
 * 受付には「焼きはじめ（→調理中）」と「焼き上がり（→お渡し可）」が並びますが、
 * 後者は<b>ドリンクだけの注文で焼く工程を飛ばす</b>ための操作です。
 * それが READY 行きというだけで緑の目立つボタンになり、
 * 本来の一手である「焼きはじめ」は白いゴーストで一番弱い、という状態でした。
 * 忙しい厨房で、いちばん目立つボタンが飛ばし技なのは事故のもとです。
 *
 * <p><b>なぜ描画してまで確かめるのか</b><br>
 * 見た目の話なのでテストしづらく、実際これまで誰も気づきませんでした。
 * ただし「どのクラスが付いたか」は HTML の属性として残るので、そこは検証できます。
 * 判断そのもの（{@code OrderStatus#primaryNext}）は
 * {@code OrderStatusTest} が素の JUnit で固定していますが、
 * <b>テンプレートがその判断を本当に使っているか</b>は Java のコンパイルでは分かりません。
 * Thymeleaf の式は文字列なので、書き間違えても 200 で描画され続けます。
 * だからこのクラスは、ページを実際に描かせて class 属性を読みます。
 *
 * <p>{@code @Transactional} を付けないのは、{@code open-in-view: false} の本番と
 * 同じトランザクション境界で描画させるためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("厨房ボードのボタンの強弱")
class KitchenBoardButtonStyleTest {

    /** 主ボタン（スタッフ側テーマでは黒の塗り）。 */
    private static final String PRIMARY = "btn--primary";
    /** 最後の確定操作だけを大きくする修飾。 */
    private static final String LARGE = "btn--lg";

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

    private Order placed;

    @BeforeEach
    void setUp() {
        clearAll();

        // 「いつ走らせても同じ結果」にするため受付条件を固定する。
        // 営業日の切り替えを 0 時にしておかないと、深夜に走らせたとき
        // 注文がボードに出ず、フォームが 1 つも描かれないテストになる。
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("鉄板おつまみ", 10));
        MenuItem item = menuItemRepository.save(new MenuItem(category, "鉄板わかめ焼き（北海道産）", 580));

        DiningTable table = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10));
        TableSession bill = tableService.openSession(table.getId(), 2);

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 1);
        // ボードに出す注文は<b>ちょうど 1 件</b>にしてある。
        // 2 件あるとレーンをまたいで同じ遷移先（COOKING）のボタンが 2 つ出てしまい、
        // 「どちらのチケットのボタンか」を HTML から選り分ける必要が出る。
        // テストごとに 1 件を目的の状態まで進めるほうが、読む側にも分かりやすい。
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
    @DisplayName("受付レーンでは「焼きはじめ（→調理中）」が主ボタン。飛ばし技の「焼き上がり」は副")
    void receivedLaneMakesStartCookingThePrimaryButton() throws Exception {
        Map<String, String> buttons = statusButtonClasses();

        // ★ この 2 行がこの修正の本体。以前は逆になっていた。
        assertThat(buttons.get("COOKING")).contains(PRIMARY);
        assertThat(buttons.get("READY")).doesNotContain(PRIMARY);

        // 飛ばし技はアクセント色を持たない。緑（btn--ok）で目立たせていたのをやめた箇所。
        assertThat(buttons.get("READY")).doesNotContain("btn--ok");
        // 大きくしてよいのは伝票を閉じる最後の確定操作だけ
        assertThat(buttons.get("COOKING")).doesNotContain(LARGE);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("調理中レーンでは「焼き上がり（→お渡し可）」が主ボタン")
    void cookingLaneMakesReadyThePrimaryButton() throws Exception {
        orderService.changeStatus(placed.getId(), OrderStatus.COOKING, "厨房スタッフ");

        Map<String, String> buttons = statusButtonClasses();

        // 受付レーンでは副だった READY が、調理中レーンでは主になる。
        // 「遷移先の名前で決めていない」ことが、この 1 行で分かる。
        assertThat(buttons.get("READY")).contains(PRIMARY);
        assertThat(buttons.get("READY")).doesNotContain(LARGE);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("提供待ちレーンでは「提供済みにする」が主ボタンかつ大。「調理中に戻す」は副")
    void readyLaneKeepsCompleteAsTheLargePrimaryButton() throws Exception {
        orderService.changeStatus(placed.getId(), OrderStatus.COOKING, "厨房スタッフ");
        orderService.changeStatus(placed.getId(), OrderStatus.READY, "厨房スタッフ");

        Map<String, String> buttons = statusButtonClasses();

        // 伝票を閉じる最後の確定操作。指のサイズで区別する意図は壊さない。
        assertThat(buttons.get("COMPLETED")).contains(PRIMARY).contains(LARGE);

        // 同じ COOKING でも、ここでは「焼き直すために一段戻す」操作なので副。
        // 受付レーンの COOKING（主ボタン）と見た目が違うことが、
        // 遷移先ではなくレーンの文脈で決めている証拠になる。
        assertThat(buttons.get("COOKING")).doesNotContain(PRIMARY);
    }

    // ========================================================================
    //  描き出された HTML を読むための道具
    // ========================================================================

    /** {@code <form>…</form>} を 1 つずつ取り出す（{@code DOTALL} で改行をまたぐ）。 */
    private static final Pattern FORM = Pattern.compile("<form[^>]*>(.*?)</form>", Pattern.DOTALL);
    /** そのフォームが送る遷移先（{@code <input name="status" value="COOKING">}）。 */
    private static final Pattern STATUS_VALUE =
            Pattern.compile("name=\"status\"\\s+value=\"([A-Z]+)\"");
    /** そのフォームのボタンに付いた class 属性。 */
    private static final Pattern BUTTON_CLASS =
            Pattern.compile("<button[^>]*?class=\"([^\"]*)\"", Pattern.DOTALL);

    /**
     * 厨房ボードを描画し、「遷移先 → そのボタンの class 属性」に畳んで返す。
     *
     * <p>状態を送らないフォーム（キャンセル・ログアウト）は入りません。
     * ボードに出す注文を 1 件に絞っているので、遷移先はぶつかりません。
     */
    private Map<String, String> statusButtonClasses() throws Exception {
        String html = mockMvc.perform(get("/kitchen"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, String> classes = new LinkedHashMap<>();
        Matcher forms = FORM.matcher(html);
        while (forms.find()) {
            String form = forms.group(1);
            Matcher statusValue = STATUS_VALUE.matcher(form);
            Matcher buttonClass = BUTTON_CLASS.matcher(form);
            if (statusValue.find() && buttonClass.find()) {
                classes.put(statusValue.group(1), buttonClass.group(1));
            }
        }

        // 「正規表現が何も拾えず、全部の assertThat(null) が素通りする」のが
        // このやり方の一番こわい壊れ方なので、先にここで気づけるようにしておく。
        assertThat(classes)
                .as("厨房ボードから状態遷移のボタンを 1 つも読み取れていない"
                        + "（テンプレートの構造が変わった可能性）")
                .isNotEmpty();
        return classes;
    }
}
