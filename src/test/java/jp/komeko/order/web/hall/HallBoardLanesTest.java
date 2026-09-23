package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ホール盤面を、厨房ボードと同じ「3 列」にした（2026-09-12。設計 540:3509／540:3496／453:5744）。
 *
 * <p><b>列は卓の状態そのものです。</b>
 * <ul>
 *   <li>在席 …… 伝票が開いていて、まだお会計待ちに入っていない</li>
 *   <li>お会計待ち …… {@code TableSession#isClosing()}。この間その卓からは注文できない</li>
 *   <li>片付け待ち …… 伝票が無く、卓の {@code needsCleanup} が立っている</li>
 * </ul>
 *
 * <p><b>片付け待ちの列だけ中身が違います。</b>
 * 会計が済んだ卓には伝票が無いので、人数・滞在・金額を出す先がありません。
 * 存在しない数字を出さないために、この列は卓名・案内文・ボタンだけにしています。
 *
 * <p>列の判定には見出しの文字ではなく <b>lane--* のクラス名</b>を使います。
 * 「お会計待ち」は札の文字としてカードの中にも出るため、
 * 文字で切ると必ず取り違えるためです。
 *
 * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
 * 本番と同じ形で描画させるためです（{@code HallBoardDesignTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホール盤面の 3 列（在席／お会計待ち／片付け待ち）")
class HallBoardLanesTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    @BeforeEach
    void setUp() {
        clearAll();
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);
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

    private DiningTable seated() {
        DiningTable t = diningTableRepository.save(new DiningTable("在席テーブル", 4, 10));
        tableService.openSession(t.getId(), 2);
        return t;
    }

    private DiningTable awaitingCheckout() {
        DiningTable t = diningTableRepository.save(new DiningTable("会計待ちテーブル", 4, 20));
        TableSession bill = tableService.openSession(t.getId(), 3);
        tableService.startCheckout(bill.getId());
        return t;
    }

    private DiningTable awaitingCleanup() {
        DiningTable t = new DiningTable("片付けテーブル", 4, 30);
        t.setNeedsCleanup(true);
        return diningTableRepository.save(t);
    }

    private String board() throws Exception {
        return mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * 指定した列の中身だけを切り出す。
     * 見出しの文字ではなくクラス名で切るのは、上のクラスコメントの理由による。
     */
    private String lane(String html, String laneClass) {
        int from = html.indexOf(laneClass);
        assertThat(from).as(laneClass + " の列が無い").isGreaterThan(0);
        int to = html.indexOf("lane--", from + laneClass.length());
        return to > from ? html.substring(from, to) : html.substring(from);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 3 つの卓が、それぞれの列にひとつずつ振り分けられる")
    void eachTableLandsInItsOwnLane() throws Exception {
        seated();
        awaitingCheckout();
        awaitingCleanup();

        String html = board();

        assertThat(lane(html, "lane--seated")).contains("在席テーブル");
        assertThat(lane(html, "lane--closing")).contains("会計待ちテーブル");
        assertThat(lane(html, "lane--cleanup")).contains("片付けテーブル");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ お会計待ちの卓は在席の列に出ない（二重に見えると会計を二度打つ）")
    void awaitingCheckoutLeavesTheSeatedLane() throws Exception {
        seated();
        awaitingCheckout();

        String seatedLane = lane(board(), "lane--seated");

        assertThat(seatedLane).contains("在席テーブル");
        assertThat(seatedLane).as("お会計待ちの卓が在席にも出ている")
                .doesNotContain("会計待ちテーブル");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 片付け待ちのカードは金額を出さない（伝票が無いので出す先がない）")
    void cleanupCardHasNoAmount() throws Exception {
        awaitingCleanup();

        String cleanupLane = lane(board(), "lane--cleanup");

        assertThat(cleanupLane).contains("片付けテーブル");
        assertThat(cleanupLane).as("存在しない請求額を出している")
                .doesNotContain("現在のご請求額");
        // 金額の代わりに出すのは席数（2026-09-14。もとは説明文
        // 「片付けがすんだら…」だったが、当たり前すぎるので席数に替えた。
        // 経緯は HallStatMergeTest）
        assertThat(cleanupLane).contains("名席");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 片付け完了を押すと旗が下りて、卓が列から消える")
    void cleanupButtonClearsTheFlag() throws Exception {
        DiningTable table = awaitingCleanup();

        assertThat(lane(board(), "lane--cleanup")).contains("/hall/tables/" + table.getId() + "/cleaned");

        mockMvc.perform(post("/hall/tables/" + table.getId() + "/cleaned").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(diningTableRepository.findById(table.getId()).orElseThrow().isNeedsCleanup())
                .isFalse();
        assertThat(lane(board(), "lane--cleanup")).doesNotContain("片付けテーブル");
    }

    /**
     * ★ 盤面のボタンは伝票ページへ渡すだけ（2026-09-23 にモーダルをやめました）。
     *
     * <p><b>守っているものは変わっていません</b>——「1 回押しただけでは締まらない」。
     * 以前はモーダルを開いて、その中に支払方法と締めるフォームがありました。
     * いまは伝票ページ（{@code /hall/bills/{id}}）へ渡します。
     *
     * <p><b>なぜページにしたか。</b>
     * モーダルは 520px で、14 品の明細のうち 8 品しか一度に出せず、
     * お客さまに読み上げながらスクロールすることになっていました。
     * 伝票ページは同じ内容をページ幅で出せて、しかも会計メモ・人数変更・
     * チャージ免除・お会計待ちにする、を最初から持っています。
     *
     * <p><b>盤面に締めるフォームが無いことを見張ります。</b>
     * 盤面に残っていると、卓を眺めているだけのつもりで押せてしまいます。
     * 会計は売上が確定する操作なので、伝票を開いた人にだけ触らせます。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ お会計待ちのボタンは伝票ページへ渡す（盤面では締められない）")
    void checkoutButtonGoesToTheBillPage() throws Exception {
        DiningTable table = awaitingCheckout();
        Long billId = tableService.currentSession(table.getId()).orElseThrow().getId();

        String html = board();

        assertThat(lane(html, "lane--closing"))
                .as("★ お会計待ちの列から伝票ページへ行けない")
                .contains("/hall/bills/" + billId);

        assertThat(html)
                .as("★ 盤面に締めるフォームが残っている。卓を見ているだけのつもりで押せてしまう")
                .doesNotContain("/hall/bills/" + billId + "/close");
        assertThat(html)
                .as("★ 盤面に支払方法が残っている。会計は伝票ページの仕事")
                .doesNotContain("name=\"paymentMethod\"");

        // 行き先の伝票ページに、支払方法と締めるフォームが揃っていること
        String bill = mockMvc.perform(get("/hall/bills/" + billId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bill).as("伝票ページに支払方法が無い").contains("name=\"paymentMethod\"");
        assertThat(bill).as("伝票ページに締めるフォームが無い")
                .contains("/hall/bills/" + billId + "/close");
        // ★ 確認ダイアログは最後のボタンだけ。途中のボタンには付けない
        assertThat(bill).contains("komekoConfirmClose");
    }
}
