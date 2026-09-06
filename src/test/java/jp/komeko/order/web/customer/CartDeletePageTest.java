package jp.komeko.order.web.customer;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 注文リストから 1 品消す動線（設計 暗06 の「−」と 暗26 の確認）。
 *
 * <p><b>何を守っているか</b><br>
 * 消す操作は、押し間違えたときに<b>手がかりが残りません</b>。
 * 行が消えると「何が入っていたか」を思い出す材料も一緒に消えます。
 * だから「押す前に何を消すのか出す」ことと、
 * 「間違えて押しやすい側に安全な選択肢を置く」ことを固定します。
 *
 * <p>行の「−」は品名のすぐ左にあり、同じ行の中で「変更」と隣り合います。
 * ここは 1cm ずれただけで結果が正反対になる場所です。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("注文リストから 1 品消す（暗06 / 暗26）")
class CartDeletePageTest {

    private static final Path CART_HTML =
            Path.of("src/main/resources/templates/customer/cart.html");
    private static final Path APP_CSS =
            Path.of("src/main/resources/static/css/app.css");

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
    private MenuItem soba;
    private MenuItem drink;

    @BeforeEach
    void setUp() throws Exception {
        clearAll();

        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        shopSettingService.save(setting);

        Category category = categoryRepository.save(new Category("鉄板焼き", 10));

        // 写真のある品。設計（暗26）の確認には写真が出る
        MenuItem withPhoto = new MenuItem(category, "肉玉米粉そば", 1180);
        withPhoto.setImagePath("/images/menu/okonomi.jpg");
        soba = menuItemRepository.save(withPhoto);

        // 写真の無い品。ドリンクは写真を持たないものが多く、
        // そのときは文字だけの確認になる
        drink = menuItemRepository.save(new MenuItem(category, "生ビール（中）", 580));

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        browser = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(browser));
        mockMvc.perform(post("/t/" + table.getAccessToken() + "/start")
                .with(csrf()).session(browser).param("guestCount", "2"));
        mockMvc.perform(post("/cart/add").with(csrf()).session(browser)
                .param("menuItemId", String.valueOf(soba.getId()))
                .param("quantity", "2"));
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

    private String cart() throws Exception {
        return mockMvc.perform(get("/cart").session(browser))
                .andReturn().getResponse().getContentAsString();
    }

    // ========================================================================

    @Test
    @DisplayName("★ 行に「−」があり、その行の品を消す先につながっている")
    void eachRowHasItsOwnDeleteControl() throws Exception {
        String page = cart();

        assertThat(page).as("「−」が無い").contains("order-row__delbtn");
        assertThat(page).as("消す先につながっていない").contains("/cart/remove");
        // どの行を消すのかは key で決まる。ここが抜けると、
        // 押しても「どれを」消せばよいかサーバに伝わらない
        assertThat(page).as("行を特定する key が無い").contains("name=\"key\"");
    }

    @Test
    @DisplayName("★ 押す前に「何を消すのか」を出せる材料が付いている")
    void carriesWhatWillBeDeleted() throws Exception {
        String page = cart();

        // 行が並んだ画面で「削除しますか」だけ出しても、
        // どれを消すつもりだったのか確かめようがない
        assertThat(page).as("消す対象の名前を持っていない")
                .contains("data-confirm-name=\"肉玉米粉そば ×2\"");
        assertThat(page).as("確認そのものが無い").contains("id=\"confirm-remove\"");
        // 「すべて」は削り落とさないこと（2026-09-06 決定）。
        // 「−」という記号は「1 つ減らす」と読めるが、実際は行ごと消える。
        // ×3 の行で 1 つ減るつもりで押した人に、この 3 文字が「そうではない」と伝える。
        // ここが「この項目を削除しますか」に戻ると、記号と挙動の食い違いが
        // 確認の文言で受け止められなくなる
        assertThat(page).contains("この品をすべて削除しますか");
    }

    @Test
    @DisplayName("★ 写真のある品は、その写真を確認に出せる（設計 暗26）")
    void carriesThePhotoWhenTheItemHasOne() throws Exception {
        String page = cart();

        // 文字だけより速い。「肉玉米粉そば ×1」を読んで確かめるより、
        // 写真を見るほうが「これで合っている」と分かるのが早い
        assertThat(page).as("写真を渡していない")
                .contains("data-confirm-image=\"/images/menu/okonomi.jpg\"");
        assertThat(page).as("確認に写真の置き場が無い").contains("data-confirm-photo");
    }

    @Test
    @DisplayName("★ 写真の無い品には、写真の属性ごと付けない")
    void omitsThePhotoWhenThereIsNone() throws Exception {
        mockMvc.perform(post("/cart/add").with(csrf()).session(browser)
                .param("menuItemId", String.valueOf(drink.getId()))
                .param("quantity", "1"));

        String page = cart();
        int at = page.indexOf("生ビール");
        String rowStart = page.substring(page.lastIndexOf("<form", at), at);

        // 属性が空文字で付くと、JavaScript 側で「写真あり」と読めてしまい、
        // 壊れた画像の印が出る端末がある。付けないのが正しい
        assertThat(rowStart).as("写真の無い品に画像の属性が付いている")
                .doesNotContain("data-confirm-image");
    }

    @Test
    @DisplayName("★ 写真は隠した状態で置いてある（前の品の写真が残らない）")
    void startsWithTheePhotoHidden() throws Exception {
        String page = cart();
        int at = page.indexOf("data-confirm-photo");
        String tag = page.substring(page.lastIndexOf("<img", at), page.indexOf('>', at) + 1);

        // 写真の無い品を選んだときに、前に開いた品の写真が残っていると、
        // 別のものを消そうとしているように見える
        assertThat(tag).as("最初から出ている").contains("hidden");
        // alt は空。すぐ下に品名が出ているので、読み上げで 2 回言わせない
        assertThat(tag).as("読み上げで品名を 2 回言うことになる").contains("alt=\"\"");
    }

    @Test
    @DisplayName("★ 目立つほうが「やめる」（取り返せる間違いに倒す）")
    void putsTheSafeChoiceOnTheEmphasisedButton() throws Exception {
        String page = cart();

        int cancel = page.indexOf("data-confirm-cancel");
        int ok = page.indexOf("data-confirm-ok");
        assertThat(cancel).isGreaterThan(-1);
        assertThat(ok).isGreaterThan(-1);

        // 間違えて「やめる」を押しても何も起きないが、
        // 間違えて「削除する」を押すと戻せない。
        // きつね色（btn--primary）が付くのは、取り返せるほうでなければならない
        String cancelButton = page.substring(page.lastIndexOf("<button", cancel), cancel);
        String okButton = page.substring(page.lastIndexOf("<button", ok), ok);
        assertThat(cancelButton).as("「やめる」が目立つ側になっていない").contains("btn--primary");
        assertThat(okButton).as("「削除する」が目立つ側になっている").doesNotContain("btn--primary");
    }

    @Test
    @DisplayName("★ 実際に消える（消したら行が減る）")
    void actuallyRemovesTheLine() throws Exception {
        String before = cart();
        assertThat(before).contains("肉玉米粉そば");

        // 行の key を画面から拾って、そのまま送る（画面が出している値で消せること）
        int at = before.indexOf("name=\"key\" value=\"");
        String key = before.substring(at + 18, before.indexOf('"', at + 18));

        mockMvc.perform(post("/cart/remove").with(csrf()).session(browser).param("key", key));

        assertThat(cart()).as("消えていない").doesNotContain("肉玉米粉そば");
    }

    @Test
    @DisplayName("★ 「注文を取り消す」は下の帯の中のボタン（文字リンクではない）")
    void showsTheClearButtonInsideTheFixedBar() throws Exception {
        String page = cart();

        int bar = page.indexOf("class=\"submitbar\"");
        assertThat(bar).as("下の帯が無い").isGreaterThan(-1);
        String barHtml = page.substring(bar, page.indexOf("</div>", page.indexOf("注文を取り消す")));

        assertThat(barHtml).as("帯の中に取り消しが無い").contains("注文を取り消す");
        assertThat(barHtml).as("枠線のボタンになっていない").contains("btn--outline");
        // 帯は「注文する」フォームの中にある。フォームは入れ子にできないので、
        // form 属性で外の取り消しフォームへ送る。ここが抜けると押しても何も起きない
        assertThat(barHtml).as("外のフォームへつないでいない").contains("form=\"clear-cart\"");
        assertThat(page).contains("id=\"clear-cart\"");
    }

    @Test
    @DisplayName("★ 帯が 2 段になったぶん、本文の逃げ場も広げてある")
    void leavesRoomForTheTallerBar() throws Exception {
        // ここを直し忘れると、最後の行が帯の下に潜って金額が読めないまま押すことになる。
        // ボタン 2 つ＋余白で 24+56+12+56+32 = 180 なので、それ以上要る
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(".submitbar-room");
        String rule = css.substring(at, css.indexOf('}', at));

        int room = Integer.parseInt(rule.replaceAll("[^0-9]", ""));
        assertThat(room).as("逃げ場が帯の高さに足りない").isGreaterThanOrEqualTo(180);
    }

    @Test
    @DisplayName("「−」の的は行の高さいっぱい（丸だけだと 32px しかない）")
    void makesTheHitAreaTallerThanTheCircle() throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(".order-row__delbtn");
        String rule = css.substring(at, css.indexOf('}', at));

        // align-self: stretch で行の高さに伸ばしている。
        // 固定の高さを書くと、行が高いときに的が足りず、低いときに行を押し広げる
        assertThat(rule).as("的を行の高さに合わせていない").contains("align-self: stretch");
    }

    @Test
    @DisplayName("JavaScript が動かなくても消せる（素の form として成立している）")
    void worksWithoutJavaScript() throws Exception {
        String page = Files.readString(CART_HTML);

        // onclick に頼らず、button[type=submit] を form で囲ってある。
        // 確認は出なくなるが、消せなくなるよりよい
        // （消えるのは注文前のカートの 1 行で、入れ直せる）
        int at = page.indexOf("order-row__del\"");
        String block = page.substring(at, page.indexOf("</form>", at));
        assertThat(block).contains("method=\"post\"");
        assertThat(block).contains("type=\"submit\"");
    }
}
