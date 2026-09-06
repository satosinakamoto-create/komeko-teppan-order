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
import org.junit.jupiter.api.Nested;
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
 * 「しばらくしたら消えるお知らせ」と「消してはいけない案内」の切り分け。
 *
 * <p><b>何を守っているか</b><br>
 * お客さま側の {@code customer.js} は、用が済んだ報告を 4 秒で引っ込めます。
 * 引っ込めないと「注文リストに追加しました」がメニューに居座り続け、
 * 次に本当に伝えたいことが埋もれるためです。
 *
 * <p>問題は<b>どれを消すかの選び方</b>でした。もとは {@code .alert--info} という
 * <b>色のクラス</b>で選んでいました。色は見た目の指定でしかなく、
 * 常設の案内文にも同じ色が使われています。その結果、消してはいけない 3 つが
 * 4 秒で消えていました（2026-09-06 にスマホ画面の動作確認で発見）。
 *
 * <ul>
 *   <li>注文リスト画面の「◯番テーブルへお持ちします。お会計はレジまで」</li>
 *   <li>卓に入った直後の、店主が書いたお客さまへのご案内</li>
 *   <li>公開デモの「これはお客さま側の画面です」という説明</li>
 * </ul>
 *
 * <p>いちばん困るのは 3 つめです。<b>見学者が読んでいる最中に説明が消えます。</b>
 * しかも例外は出ず、画面も壊れません。ただ文が無くなるだけなので、
 * 消えたあとに画面を見た人には、はじめから書いていないようにしか見えません。
 *
 * <p>そこで「消してよい」という判断を、出した側（{@code fragments/common.html}）が
 * {@code is-transient} という印で伝える形にしました。このテストは
 * <b>印を付ける側と見る側の両方</b>を押さえます。片方だけ直すと元に戻るためです。
 */
@DisplayName("消えるお知らせと、消してはいけない案内")
class TransientNoticeTest {

    /**
     * 見る側（JavaScript）。
     *
     * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
     * ブラウザで動かさなくても、<b>選び方が色に戻っていないか</b>は文字列で分かります。
     */
    @Nested
    @DisplayName("消す側（customer.js）")
    class Remover {

        private static final Path CUSTOMER_JS = Path.of("src/main/resources/static/js/customer.js");

        @Test
        @DisplayName("★ 印（is-transient）で選ぶ。色（alert--info）で選ばない")
        void picksByMarkerNotByColour() throws Exception {
            String js = Files.readString(CUSTOMER_JS);

            // 消す対象を集めている 1 行を取り出す。
            // ファイル全体で探すと、説明のコメントに書いた「.alert--info」に反応してしまう
            String selectorLine = js.lines()
                    .filter(line -> line.contains("querySelectorAll") && line.contains("alert"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("お知らせを集めている行が見つかりません"));

            assertThat(selectorLine)
                    .as("印ではなく色で選んでいる。常設の案内文まで消える")
                    .contains("is-transient");
            assertThat(selectorLine)
                    .as("色のクラスで選んでいる。新しい案内文を足すたびに巻き込まれる")
                    .doesNotContain("alert--info")
                    .doesNotContain("alert--success");
        }

        @Test
        @DisplayName("★ 設計（暗25）の見た目が入っている：四辺を囲う・角丸 6・中央ぞろえ・改行を活かす")
        void matchesTheDesign() throws Exception {
            String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));

            int start = css.indexOf(".theme-night .alert--success");
            assertThat(start).as("お客さま側の成功の札に、設計の指定が無い").isGreaterThan(-1);
            String block = css.substring(start, css.indexOf('}', start));

            // 左だけ 4px → 四辺 1px（.alert の border-left を上書きする）
            assertThat(block).as("四辺を囲っていない").contains("border: 1px solid");
            // --r-sm は 8px。設計は 6px なので、この札にだけ効かせている
            assertThat(block).as("角丸が設計の 6px でない").contains("border-radius: 6px");
            assertThat(block).as("中央ぞろえでない").contains("text-align: center");

            // 色は書かない。--ok / --ok-soft が設計の #7fbf9a / #1e2a24 と同じなので、
            // ここに hex を書くとテーマの色を変えたときにここだけ取り残される
            assertThat(block).as("色を直書きしている").doesNotContain("#");

            // 改行の指定は「箱」ではなく「中の p」に付ける。
            // 箱に付けると、テンプレートの字下げまで行として数えられ、
            // 上下に空行が 3 つずつ入って高さが 77 → 131px になる（実機で確認済み）
            assertThat(css).as("改行が空白に潰れる")
                    .contains(".theme-night .alert--success p { white-space: pre-line; }");
            assertThat(block).as("改行の指定を箱に付けている。上下に空行が入る")
                    .doesNotContain("white-space");
        }

        @Test
        @DisplayName("★ 札の改行は、出す側が持っている（幅まかせにしない）")
        void breaksTheLineOnPurpose() {
            // 幅まかせだと「…確定し／ていません」のような場所で折れ、
            // しかも折り返す位置は端末の幅で変わる（390 と 360 で別の場所）
            assertThat(jp.komeko.order.web.customer.CartController.ADDED_TO_CART_MESSAGE)
                    .isEqualTo("注文リストに追加しました\n（まだ注文は確定していません）");
        }

        @Test
        @DisplayName("★ 外すのは、消え終わったあと（途中で外すと下がガクンと跳ねる）")
        void removesAfterTheFadeHasFinished() throws Exception {
            String js = Files.readString(CUSTOMER_JS);

            // 消える時間と、DOM から外すまでの待ちが別々の数字で書かれていると、
            // 時間を延ばした瞬間に破綻する（0.3 秒の変化に 350ms 待ちで
            // たまたま間に合っていた、というのが元の状態）。
            // 片方から導いていることを固定する
            assertThat(js).as("外すまでの待ちを、消える時間から導いていない")
                    .contains("var NOTICE_REMOVE_MS = NOTICE_FADE_MS +");

            // 実際に使われているのが導いた値であること。
            // 定数を作っただけで生の数字を渡していては意味がない
            assertThat(js).as("外すときに導いた値を使っていない")
                    .contains("box.remove(); }, NOTICE_REMOVE_MS)");
            assertThat(js).as("消える時間を transition に渡していない")
                    .contains("NOTICE_FADE_MS + 'ms ease");
        }

        @Test
        @DisplayName("動きを控えめにしたい人には、動かさずに消す")
        void respectsReducedMotion() throws Exception {
            String js = Files.readString(CUSTOMER_JS);

            // ゆっくりにしたぶん、動きが苦手な人には変化が長く残る。
            // 消えること自体は必要なので、動かさずに消すだけにする
            assertThat(js).contains("prefers-reduced-motion");
            assertThat(js).contains("box.remove()");
        }

        @Test
        @DisplayName("エラーは消さない（読み落とすと理由が分からなくなる）")
        void keepsErrors() throws Exception {
            String js = Files.readString(CUSTOMER_JS);
            String selectorLine = js.lines()
                    .filter(line -> line.contains("querySelectorAll") && line.contains("alert"))
                    .findFirst().orElseThrow();

            assertThat(selectorLine).doesNotContain("alert--error");
        }
    }

    /**
     * 印を付ける側（画面）。
     *
     * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
     * 本番と同じ形で描画させるためです。
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("印を付ける側（画面）")
    class Marker {

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
        private MenuItem item;

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
            item = menuItemRepository.save(new MenuItem(category, "肉玉米粉そば", 1180));

            DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));

            // QR を読んで卓に着き、人数を答えたところまで進める
            browser = new MockHttpSession();
            mockMvc.perform(get("/t/" + table.getAccessToken()).session(browser));
            mockMvc.perform(post("/t/" + table.getAccessToken() + "/start")
                    .with(csrf()).session(browser).param("guestCount", "2"));
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

        @Test
        @DisplayName("★ 1 回きりの報告には印が付く（消えてよい）")
        void marksOneOffReports() throws Exception {
            mockMvc.perform(post("/cart/add").with(csrf()).session(browser)
                    .param("menuItemId", String.valueOf(item.getId()))
                    .param("quantity", "1"));

            // 追加のあとメニューへ戻される。そこに出る「追加しました」が対象
            String menu = mockMvc.perform(get("/menu").session(browser))
                    .andReturn().getResponse().getContentAsString();

            assertThat(menu).contains("注文リストに追加しました");
            assertThat(menu).as("1 回きりの報告に印が付いていない。ずっと居座る")
                    .contains("is-transient");
            // 設計どおり 2 行で出す。改行は文言側が持っていて、
            // th:text は文字として書き出すので HTML にもそのまま現れる
            assertThat(menu).as("設計の改行が消えている")
                    .contains("注文リストに追加しました\n（まだ注文は確定していません）");
        }

        @Test
        @DisplayName("★ 常設の案内には印を付けない（読んでいる最中に消えない）")
        void neverMarksStandingNotices() throws Exception {
            mockMvc.perform(post("/cart/add").with(csrf()).session(browser)
                    .param("menuItemId", String.valueOf(item.getId()))
                    .param("quantity", "1"));

            // 注文リストの画面を、フラッシュが出ない形で開く。
            // ここに残る「お持ちします／レジまで」は常設の案内
            String cart = mockMvc.perform(get("/cart").session(browser))
                    .andReturn().getResponse().getContentAsString();

            assertThat(cart).as("常設の案内そのものが消えている").contains("レジまで");
            assertThat(cart).as("常設の案内に印が付いている。4 秒で消える")
                    .doesNotContain("is-transient");
        }

        @Test
        @DisplayName("★ 卓に着いた直後のご案内にも印を付けない")
        void neverMarksTheWelcomeNotice() throws Exception {
            DiningTable another = diningTableRepository.save(new DiningTable("4番テーブル", 4, 20));

            // 人数を答える前の画面。店主が書いたご案内（pickupNotice）が出る
            String start = mockMvc.perform(get("/t/" + another.getAccessToken()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(start).as("ご案内に印が付いている。読む前に消える")
                    .doesNotContain("is-transient");
        }
    }
}
