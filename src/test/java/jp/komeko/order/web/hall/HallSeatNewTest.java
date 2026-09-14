package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.ShopSetting;
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
 * 「＋新規お客様」からご案内する流れ（2026-09-11）。
 *
 * <p><b>何を変えたか</b><br>
 * 盤面から「片付け待ち」と「空席」の 2 つの枠を外し、
 * 入口を {@code ＋新規お客様} ボタン 1 つにまとめました。
 * 人数と卓は次の画面（{@code /hall/seat/new}）で選びます。
 *
 * <p><b>片付け待ちの卓を選べることが要点です。</b>
 * 以前は「片付け完了」を押してから「ご案内」を押す 2 手でした。
 * 皿を下げた人と案内する人は同じなので、1 手にまとめています。
 * <b>片付け完了は人の判断なので、記録は残します</b>（旗を下ろす操作は同じ）。
 *
 * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
 * 本番と同じ形で描画させるためです（{@code HallBoardDesignTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("＋新規お客様からご案内する")
class HallSeatNewTest {

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
        // 何時に走らせても同じ結果になるようにそろえる
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

    private DiningTable vacantTable() {
        return diningTableRepository.save(new DiningTable("テーブル4", 4, 10));
    }

    /** 会計が済んで、まだ皿が残っている卓。 */
    private DiningTable cleanupTable() {
        DiningTable table = new DiningTable("カウンター札2", 6, 20);
        table.setNeedsCleanup(true);
        return diningTableRepository.save(table);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 空席にご案内すると、その卓の伝票が開く")
    void seatsGuestsAtAVacantTable() throws Exception {
        DiningTable table = vacantTable();

        mockMvc.perform(post("/hall/seat").with(csrf())
                        .param("tableId", String.valueOf(table.getId()))
                        .param("guestCount", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(tableService.currentSession(table.getId()))
                .as("伝票が開いていない").isPresent();
        assertThat(tableService.currentSession(table.getId()).orElseThrow().getGuestCount())
                .isEqualTo(3);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 片付け待ちの卓を選ぶと、片付け完了とご案内が 1 手で済む")
    void seatingACleanupTableAlsoMarksItCleaned() throws Exception {
        DiningTable table = cleanupTable();

        mockMvc.perform(post("/hall/seat").with(csrf())
                        .param("tableId", String.valueOf(table.getId()))
                        .param("guestCount", "2"))
                .andExpect(status().is3xxRedirection());

        // 伝票が開いていること（＝片付け待ちで弾かれていない）
        assertThat(tableService.currentSession(table.getId()))
                .as("片付け待ちで弾かれて伝票が開いていない").isPresent();
        // 旗が下りていること。ここが残ると、盤面と実態がズレたままになる
        assertThat(diningTableRepository.findById(table.getId()).orElseThrow().isNeedsCleanup())
                .as("片付け完了が記録されていない").isFalse();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 入力画面には、片付け待ちの卓も「片付け待ち」と分かる形で並ぶ")
    void theFormShowsCleanupTablesToo() throws Exception {
        vacantTable();
        cleanupTable();

        String html = mockMvc.perform(get("/hall/seat/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("テーブル4");
        assertThat(html).as("片付け待ちの卓が選べない").contains("カウンター札2");
        assertThat(html).as("状態が区別できない").contains("片付け待ち");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 盤面から「片付け待ち」「空席」の枠が消え、＋新規お客様 が入口になる")
    void boardHasOneEntryPointInsteadOfTwoSections() throws Exception {
        vacantTable();
        cleanupTable();

        String html = mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 見出しで判定する。「空席」は上の数字カード（stat__label）にも
        // 出るので、素の文字列で探すと必ず引っかかる
        assertThat(html).as("空席の枠が残っている").doesNotContain(">空席</h2>");
        assertThat(html).as("片付け待ちの枠が残っている").doesNotContain(">片付け待ち</h2>");
        // 2026-09-12 にモーダル化。別ページへのリンクではなく dialog を開くボタンになった
        assertThat(html).as("新しい入口が無い").contains("data-open-modal=\"seat-modal\"");
        assertThat(html).as("ご案内のモーダルが無い").contains("id=\"seat-modal\"");
        assertThat(html).contains("新規お客様");
        // 空席の数だけは残す（まだ入れられるかを一目で見るため）。
        // ★ 置き場所は 2 回動いた。数字カード（stat__label）→ 2026-09-14 に
        //   カードの段ごと畳んで、新規お客様ボタンの脇（見出しの帯）へ。
        //   経緯は HallStatMergeTest
        assertThat(html).as("空席の数まで消えている").contains("section-title__count\">空席");
    }

    @Test
    // 見学者は STAFF に加えて GUEST を持つ（GuestLoginController）。
    // GUEST だけにすると /hall\/** が STAFF 以上なので 403 になり、
    // 「ボタンが無い」ではなく「画面が開けない」を測ってしまう
    @WithMockUser(roles = {"STAFF", "GUEST"})
    @DisplayName("見学者には ＋新規お客様 を出さない（書き込みなので、ご案内と同じ線）")
    void guestsDoNotSeeTheButton() throws Exception {
        vacantTable();

        String html = mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ボタンだけでなく、モーダル本体（＝ご案内のフォーム）ごと出さない。
        // フォームが残っていると、開発者ツールから送れてしまう
        assertThat(html).as("ご案内のボタンが出ている").doesNotContain("data-open-modal=\"seat-modal\"");
        assertThat(html).as("ご案内のモーダルが残っている").doesNotContain("id=\"seat-modal\"");
        assertThat(html).as("お会計のモーダルが残っている").doesNotContain("id=\"bill-modal-");
    }
}
