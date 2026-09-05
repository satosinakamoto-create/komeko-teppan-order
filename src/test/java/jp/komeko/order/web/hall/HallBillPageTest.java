package jp.komeko.order.web.hall;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.SettlementMethod;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * ホールの伝票詳細ページ（{@code GET /hall/bills/{id}}）を実際に描画するテスト。
 *
 * <p><b>このテストが守っているもの＝会計確認ダイアログが読み上げる金額</b><br>
 * このページには「お会計（伝票を締める）」ボタンがあり、押すと
 * 「深夜料金 込みで ¥○○○」という確認ダイアログが出ます（{@code hall/bill.html}）。
 * そこに出す金額は {@code HallController#bill} が
 * {@code totalIfLateNightApplied} という名前でモデルに載せています。
 *
 * <p>ふつうは {@code bill.getTotalWithLateNight()} で足りるのですが、
 * <b>スタッフが一度深夜料金を免除した伝票</b>（{@code TableSession#lateNightWaived}）は
 * 再計算が {@code LateNightPolicy.NONE} に強制されるため {@code lateNightAmount} が常に 0 で、
 * 「付けた場合の金額」がどこにも計算されていません。
 * そのままだと、チェックを入れ直して締めたときに
 * <b>ダイアログが割増抜きの金額を「深夜料金 込み」と読み上げ、実際に締まる額のほうが高くなります</b>。
 * レジで読み上げる数字がズレる＝現場で最も避けたい事故です。
 *
 * <p>さらに、この属性が消える／名前が変わっても、
 * テンプレート側の {@code #numbers.formatInteger(totalIfLateNightApplied, 1, 'COMMA')} は
 * <b>例外も出さずに null を返します</b>（Thymeleaf の {@code #numbers} は null 入力に寛容）。
 * その結果 {@code data-total-with} 属性ごと消え、確認ダイアログが
 * <b>金額の無いまま「深夜料金 込みで」と読み上げる</b>ようになります。
 * ページは 200 で描画され続けるので、目でも既存テストでも気づけません
 * （2026-08-25 に、属性名を変えて実測。500 にはなりませんでした）。
 * だからモデルの値だけでなく、<b>描き出された HTML の中の金額</b>まで見ています。
 * Thymeleaf の式はコンパイルされないので、ビルドもサービスのテストも素通りします。
 *
 * <p>そこでこのクラスは、このページを 1 度でも<b>本当に描かせて</b>おきます。
 * {@code @Transactional} を付けないのは {@code open-in-view: false} の本番と同じ形で
 * 描画させるためです（付けると、描画時に DB 接続が無い状況を再現できません）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホールの伝票詳細ページ")
class HallBillPageTest {

    /** 商品の税込単価。 */
    private static final int UNIT_PRICE = 1000;
    /** 深夜料金の割増率（%）。 */
    private static final int LATE_NIGHT_PERCENT = 10;

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

    @BeforeEach
    void setUp() {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        // ★ 深夜帯を「ほぼ 1 日じゅう」にしておく。
        //   深夜料金は注文時刻で決まり、Order.createdAt は人が書き換えられない
        //   （事実の記録なので、そう決めてある）。テストから時刻を作れない以上、
        //   いつ走らせても割増が乗るように帯のほうを広げるのが唯一の手。
        //   終端は含まないので 23:59:59 台の 1 秒だけは対象外だが、
        //   その 1 秒はラストオーダー（同じ判定）の外でもあり、
        //   既存の HTTP テストも同じ前提で書かれている。
        setting.setLateNightStartTime(LocalTime.MIN);
        setting.setLateNightEndTime(LocalTime.of(23, 59, 59));
        setting.setLateNightSurchargePercent(LATE_NIGHT_PERCENT);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem item = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", UNIT_PRICE));

        DiningTable table = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10));
        // 伝票は来店時点の設定をコピーするので、必ず設定を保存してから開く
        bill = tableService.openSession(table.getId(), 2);

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 1);
        orderService.placeOrder(cart, bill.getId(), null);
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
    @DisplayName("200 で描画され、確認ダイアログ用の「深夜料金を付けた場合」の金額がモデルに載る")
    void billPageRendersWithDialogAmount() throws Exception {
        MvcResult result = mockMvc.perform(get("/hall/bills/{id}", bill.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("hall/bill"))
                .andExpect(model().attributeExists(
                        "bill", "orders", "totalIfLateNightApplied", "lateNightDefault"))
                .andExpect(content().string(containsString("1番テーブル")))
                .andExpect(content().string(containsString("肉玉米粉そば")))
                .andReturn();

        TableSession shown = shownBill(result);
        // 免除していない伝票では、ダイアログの金額＝いま計算されている請求額
        int dialogAmount = totalIfLateNightApplied(result);
        assertThat(dialogAmount)
                .as("免除していない伝票では recalculate 済みの金額をそのまま出す")
                .isEqualTo(shown.getTotalWithLateNight());
        // モデルに載っているだけでは足りない。ダイアログが読む属性まで届いていること
        assertThat(result.getResponse().getContentAsString())
                .as("会計確認ダイアログが読む data-total-with に金額が入っていること")
                .contains(dialogMarkup(dialogAmount));
        // 割増が 0 円だと、下のテストが何も確かめていないのと同じになる。
        // この前提が崩れていないことをここで押さえておく
        assertThat(shown.getTotalWithLateNight())
                .as("深夜料金が実際に乗っている状況で試していること")
                .isGreaterThan(shown.getTotalWithoutLateNight());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("深夜料金を免除した伝票でも 200 で描画され、ダイアログは割増ありの金額を出す")
    void waivedBillStillRendersAndKeepsSurchargedAmount() throws Exception {
        // 免除する前の「割増を付けた場合の金額」を控えておく。
        // テスト側で小計＋チャージ＋10% を組み立てないのは、
        // 計算式を 2 か所に持たないため（計算は TableSession.recalculate の 1 箇所）
        int expected = totalIfLateNightApplied(
                mockMvc.perform(get("/hall/bills/{id}", bill.getId())).andReturn());

        // スタッフが深夜料金を外して会計 → 誤会計に気づいて開け直す。
        // この経路を通った伝票だけが lateNightWaived = true のまま OPEN になる
        tableService.closeSession(bill.getId(), false, "テスト店員", null, SettlementMethod.CASH);
        tableService.reopenSession(bill.getId(), "テスト店員");
        assertThat(tableService.getSession(bill.getId()).isLateNightWaived())
                .as("この経路で免除フラグが立っていること（立っていないとテストの意味が無い）")
                .isTrue();

        MvcResult result = mockMvc.perform(get("/hall/bills/{id}", bill.getId()))
                // 免除された伝票でもページが開けること自体も、ここで固定している
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalIfLateNightApplied", expected))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .as("ダイアログが読む data-total-with は割増ありの金額であること")
                .contains(dialogMarkup(expected));

        TableSession shown = shownBill(result);
        // 画面本体は免除された（割増抜きの）金額のまま。
        // ダイアログだけが「付けた場合」を出す、というズレがこの機能の本体なので、
        // 2 つの金額が本当に別物であることまで確かめる
        assertThat(shown.getTotalWithLateNight())
                .as("表示は免除されたまま（人の判断を計算結果で上書きしない）")
                .isEqualTo(shown.getTotalWithoutLateNight());
        assertThat(expected)
                .as("ダイアログは割増ありの、より高い金額を読み上げる")
                .isGreaterThan(shown.getTotalWithLateNight());
    }

    /**
     * 会計確認ダイアログが読む属性の、期待どおりの姿。
     *
     * <p>金額表示は {@code #numbers.formatInteger(v, 1, 'COMMA')} なので
     * 3 桁区切りが入る（規約どおり）。テスト側も同じ書式で組み立てる。
     */
    private String dialogMarkup(int amount) {
        return "data-total-with=\"%,d\"".formatted(amount);
    }

    private int totalIfLateNightApplied(MvcResult result) {
        Object value = result.getModelAndView().getModel().get("totalIfLateNightApplied");
        assertThat(value)
                .as("totalIfLateNightApplied がモデルに載っていること")
                .isNotNull();
        return (Integer) value;
    }

    private TableSession shownBill(MvcResult result) {
        return (TableSession) result.getModelAndView().getModel().get("bill");
    }
}
