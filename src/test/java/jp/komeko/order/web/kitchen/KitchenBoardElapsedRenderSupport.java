package jp.komeko.order.web.kitchen;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 厨房ボードの経過時間まわりを<b>描画して</b>確かめるテストの土台。
 *
 * <p><b>なぜ親クラスに切り出したのか</b><br>
 * 見たい挙動が「実店舗」と「公開デモ」で分かれており、その違いは
 * {@code app.guest-login} という<b>起動時に決まる設定</b>です。
 * {@code KitchenController} はこの値をコンストラクタで受け取るので、
 * リクエストごとやテストメソッドごとには切り替えられません。
 * つまり<b>設定違いの 2 つのアプリを立ち上げる</b>必要があり、
 * Spring のテストでは設定が違えばテストクラスも分かれます。
 *
 * <p>{@code @Nested} クラスに {@code @TestPropertySource} を付けて
 * 中だけ設定を変える書き方も試しましたが、外側の設定が勝ってしまい効きませんでした
 * （2026-08-26 に実測。デモ側のはずのテストが 378 分を描いていた）。
 * そこで、段取りだけをここに置き、設定違いのクラスを 2 つ並べています。
 *
 * <ul>
 *   <li>{@link KitchenBoardElapsedRenderTest} … 実店舗（{@code app.guest-login} 既定の false）</li>
 *   <li>{@link KitchenBoardDemoElapsedRenderTest} … 公開デモ（true）</li>
 * </ul>
 *
 * <p>このクラス自体は {@code abstract} なので、JUnit はテストとして拾いません。
 */
abstract class KitchenBoardElapsedRenderSupport {

    /** 2026-08-24 に公開デモで実際に出ていた値。 */
    static final int STALE_MINUTES = 378;

    @Autowired
    protected MockMvc mockMvc;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Order placed;

    @BeforeEach
    void setUp() {
        clearAll();

        // 「いつ走らせても同じ結果」にするため受付条件を固定する。
        // 営業日の切り替えを 0 時にしておかないと、深夜に走らせたときに
        // 注文がボードへ出ず、チケットが 1 枚も描かれないテストになる。
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("鉄板おつまみ", 10));
        MenuItem item = menuItemRepository.save(new MenuItem(category, "鉄板チョリソー五本", 680));

        DiningTable table = diningTableRepository.save(new DiningTable("2番テーブル", 4, 20));
        TableSession bill = tableService.openSession(table.getId(), 2);

        Cart cart = new Cart();
        cartService.addToCart(cart, item.getId(), List.of(), 1);
        // ボードに出す注文はちょうど 1 件。チケットを取り違えずに読めるようにする。
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

    /**
     * 注文の受付時刻を過去へずらす。
     *
     * <p><b>なぜ SQL で直接書き換えるのか</b><br>
     * {@code Order.createdAt} は事実の記録なので、アプリ側に書き換える口を作りません
     * （CLAUDE.md「注文時刻は人が書き換えられるようにしない」）。
     * 一方このテストは「6 時間前の注文がどう描かれるか」を見たいので、
     * 時間の経過そのものを用意する必要があります。
     * setter を足すのではなく<b>テストの中だけで DB をずらす</b>ことで、
     * 本番の口を増やさずに済ませています。
     */
    protected void backdate(int minutes) {
        // ★ ちょうど N 分前ではなく、30 秒よけいに戻す。
        //
        //   経過時間は Duration#toMinutes（切り捨て）で出しています。
        //   ちょうど N 分前に置くと、記録した時刻と描画する時刻がまさに分の境目にあり、
        //   わずかな時計のぶれで N-1 分と描かれることがあります。
        //   実際 2026-08-31 に、スイート全体を流したときだけ
        //   「378 分」を期待して「377 分」になり落ちました
        //   （その test だけ流すと通るので、原因が分かるまで時間を食います）。
        //
        //   30 秒ずらせば、境目から十分に離れるので floor の結果が動きません。
        //   テストが見たいのは「6 時間前の注文が赤枠で分数付きで出るか」であって、
        //   秒単位の精度ではないので、これで意図は損ないません。
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes).minusSeconds(30)),
                placed.getId());
    }

    /** 描き出されたチケット 1 枚から読み取った、経過時間まわりの見た目。 */
    protected record Ticket(String time, boolean late) {
    }

    /** {@code <article class="ticket …">} を丸ごと 1 枚取り出す。 */
    private static final Pattern TICKET =
            Pattern.compile("<article class=\"([^\"]*ticket[^\"]*)\"(.*?)</article>", Pattern.DOTALL);
    /** そのチケットの経過時間の欄。 */
    private static final Pattern TIME =
            Pattern.compile("<span class=\"ticket__time\"[^>]*>(.*?)</span>", Pattern.DOTALL);

    /**
     * 厨房ボードを描画し、ただ 1 枚のチケットの経過時間の見た目を返す。
     *
     * <p><b>なぜ描画してまで確かめるのか</b><br>
     * Thymeleaf の式は文字列なので、Java のコンパイルでは間違いに気づけません。
     * 判断そのもの（{@code KitchenController.ElapsedDisplay}）は
     * {@link ElapsedDisplayTest} が素の JUnit で網羅していますが、
     * <b>テンプレートが本当にその判断を使って描いているか</b>はここでしか分かりません。
     */
    protected Ticket renderSingleTicket() throws Exception {
        String html = mockMvc.perform(get("/kitchen"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Matcher tickets = TICKET.matcher(html);
        assertThat(tickets.find())
                .as("厨房ボードにチケットが 1 枚も描かれていない（営業日の判定が変わった可能性）")
                .isTrue();

        String classAttribute = tickets.group(1);
        Matcher time = TIME.matcher(tickets.group(2));

        // 欄が「無い」ことも仕様のうちなので、見つからなくても失敗させません。
        // 公開デモでは、経過時間が実態と合わなくなった注文から span ごと消えます
        // （board.html の th:if）。ラベルを置く案もありましたが、7 枚中 4 枚が
        // 同じ語になってしまうためやめました。その「消えている」を time()==null で表します。
        String label = time.find() ? time.group(1).trim() : null;

        return new Ticket(label, classAttribute.contains("is-late"));
    }
}
