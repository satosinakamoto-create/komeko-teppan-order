package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.SessionStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「お会計待ち」が<b>画面に出るか</b>を確かめるテスト。
 *
 * <p><b>ドメインのテストだけでは足りない理由</b><br>
 * {@code TableSessionTest} は状態が CLOSING に変わることを確かめています。
 * けれどホールの人が見るのは画面です。状態が正しく変わっていても、
 * 一覧にしるしが出ていなければ<b>その卓が注文できない状態だと誰も気づけません</b>。
 * お客さまは注文できないまま待ち続け、店員は卓を離れたまま気づかない。
 *
 * <p>しかもこの壊れ方は例外を出しません。画面は 200 で返り、
 * 卓は今までどおり並びます。ただバッジが無いだけです。
 *
 * <p>{@code @Transactional} を付けていないのは、
 * {@code open-in-view: false} の本番と同じ形で描画させるためです
 * （{@code HallBoardOrdersTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホール画面の「お会計待ち」")
class HallClosingBadgeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
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

        // 何時に走らせても同じ結果になるようにそろえる。
        // 実店舗の設定（17:30〜翌1:30）のままだと、昼に走らせたときだけ
        // 営業時間外になって不安定なテストになる。
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        // 伝票は来店時点の設定をコピーするので、必ず設定を保存してから開く
        bill = tableService.openSession(table.getId(), 2);
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

    /** その URL を描いた HTML。 */
    private String html(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ お会計待ちにすると、一覧にバッジが出る")
    void boardShowsClosingBadge() throws Exception {
        // ご案内中のあいだは出ていないこと。
        // 最初から出ていたら、このテストは何も守っていない
        assertThat(html("/hall"))
                .as("ご案内中の卓にバッジが出てしまっている")
                .doesNotContain("お会計待ち");

        tableService.startCheckout(bill.getId());

        String board = html("/hall");
        assertThat(board).as("卓名").contains("3番テーブル");
        assertThat(board).as("★ バッジの文字").contains("お会計待ち");
        // 「止まっている」を表す既存のしるし（品切れと同じ）を使っている
        assertThat(board).as("バッジの見た目の指定").contains("badge badge--stop");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ お会計待ちの卓は、一覧から消えない（空席にならない）")
    void closingTableStaysOnTheBoard() throws Exception {
        // ここが消えると席が空いたように見え、次のお客さまを同じ卓へ案内できてしまう。
        // 伝票が 2 枚立ち、テーブルチャージも二重にかかる
        tableService.startCheckout(bill.getId());

        assertThat(html("/hall"))
                .as("お会計待ちの卓が一覧から消えている")
                .contains("3番テーブル");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("伝票の画面には状態と「ご注文を再開する」が出る")
    void billPageShowsStateAndResume() throws Exception {
        tableService.startCheckout(bill.getId());

        String page = html("/hall/bills/" + bill.getId());
        assertThat(page).as("状態の表示").contains("お会計待ち");
        assertThat(page).as("注文が止まっていることの説明").contains("この卓のスマホからは注文できません");
        assertThat(page).as("戻す手段").contains("ご注文を再開する");
        // まだ締めていないので「会計済みです」と言ってはいけない
        assertThat(page).as("会計済みと誤って表示している").doesNotContain("この伝票は会計済みです");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("ご案内中の伝票には「お会計待ちにする」ボタンが出る")
    void openBillOffersStartCheckout() throws Exception {
        assertThat(html("/hall/bills/" + bill.getId()))
                .as("お会計待ちにするボタン")
                .contains("お会計待ちにする");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("締める画面には現金／カードの選択が出る")
    void billPageOffersPaymentMethod() throws Exception {
        // 選ばないと締められないので、入力欄が無いと会計そのものができない
        String page = html("/hall/bills/" + bill.getId());
        assertThat(page).as("お支払い方法の見出し").contains("お支払い方法");
        assertThat(page).as("現金").contains("value=\"CASH\"");
        assertThat(page).as("カード").contains("value=\"CARD\"");
        // どちらも既定で選ばれていないこと（押し忘れが現金売上に化けるのを防ぐ）
        assertThat(page).as("既定で選ばれてしまっている").doesNotContain("value=\"CASH\" checked");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("再開すると、バッジも消える")
    void badgeDisappearsAfterResume() throws Exception {
        tableService.startCheckout(bill.getId());
        tableService.resumeOrdering(bill.getId());

        assertThat(tableService.getSession(bill.getId()).getStatus())
                .isEqualTo(SessionStatus.OPEN);
        assertThat(html("/hall"))
                .as("再開したのにバッジが残っている")
                .doesNotContain("お会計待ち");
    }
}
