package jp.komeko.order.service;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.SettlementMethod;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 会計済み（片付け待ち）——卓の 4 状態目（2026-09-07 / 店主と合意済み）。
 *
 * <p><b>何を守っているか</b><br>
 * 会計しても卓はすぐには使えない。片付け待ちの卓が空席に見えると、
 * 前の組の皿が残る卓に次の組を二重に案内できてしまう
 * （お会計待ちの卓が空席に見える事故と同型。あちらは既に塞いである）。
 *
 * <p><b>状態の置き場</b><br>
 * 在席かどうかはこれまでどおり伝票から導出する。卓に持つのは
 * {@code needsCleanup}——伝票からは導けない現実の事実（卓の上が
 * 片付いたか）だけ。立てるのは会計（設定 ON のとき）、下ろすのは
 * 「片付け完了」と会計取消（在席に戻るので片付け待ちではなくなる）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("会計済み（片付け待ち）の流れ")
class TableCleanupFlowTest {

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

    private boolean originalToggle;

    @BeforeEach
    void rememberToggle() {
        originalToggle = shopSettingService.currentReadOnly().isCleanupAfterCheckout();
    }

    @AfterEach
    void restoreToggle() {
        setToggle(originalToggle);
    }

    private void setToggle(boolean on) {
        ShopSetting setting = shopSettingService.current();
        setting.setCleanupAfterCheckout(on);
        shopSettingService.save(setting);
    }

    private DiningTable table() {
        return diningTableRepository.save(
                new DiningTable("片付けT" + (System.nanoTime() % 100000), 4, 992));
    }

    private void cleanUp(DiningTable table) {
        tableSessionRepository.findAll().stream()
                .filter(s -> s.getDiningTable() != null
                        && table.getId().equals(s.getDiningTable().getId()))
                .forEach(s -> tableSessionRepository.deleteById(s.getId()));
        diningTableRepository.deleteById(table.getId());
    }

    private TableSession openAndClose(DiningTable table) {
        TableSession bill = tableService.openSession(table.getId(), 2);
        return tableService.closeSession(bill.getId(), false, "テスト", null, SettlementMethod.CASH);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 会計 → 片付け待ち → ご案内したら片付け完了も記録、が一周する")
    void checkoutThenCleanupThenVacant() throws Exception {
        setToggle(true);
        DiningTable table = table();
        try {
            openAndClose(table);

            // 会計で旗が立つ（ここは 2026-09-11 の変更後も同じ）
            assertThat(diningTableRepository.findById(table.getId()).orElseThrow()
                    .isNeedsCleanup()).as("会計したのに片付け待ちになっていない").isTrue();

            // 盤面からは卓の一覧そのものが無くなった（2026-09-11）。
            // 「空席」の枠が戻っていないことだけ見る
            String board = mockMvc.perform(get("/hall"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(board).as("盤面に空席の枠が戻っている").doesNotContain(">空席</h2>");

            // 片付け待ちが分かるのは、ご案内の入力画面のほう。
            // 隠さずに並べて状態を出す、という判断（seat-new.html の説明を参照）
            String form = mockMvc.perform(get("/hall/seat/new"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(form).contains(table.getName());
            assertThat(form).as("片付け待ちだと画面から分からない").contains("片付け待ち");

            // ご案内する → 旗が下り、伝票が開く。
            // 以前は「片付け完了」→「ご案内」の 2 手だった。1 手になっても
            // <b>片付け完了が記録されること</b>は変わらない（人の判断なので）
            mockMvc.perform(post("/hall/seat").with(csrf())
                            .param("tableId", String.valueOf(table.getId()))
                            .param("guestCount", "2"))
                    .andExpect(status().is3xxRedirection());

            assertThat(diningTableRepository.findById(table.getId()).orElseThrow()
                    .isNeedsCleanup()).as("ご案内したのに片付け完了が記録されていない").isFalse();
            assertThat(tableService.currentSession(table.getId()))
                    .as("伝票が開いていない").isPresent();
        } finally {
            cleanUp(table);
        }
    }

    @Test
    @DisplayName("★ トグル OFF の店では、会計＝即空席（従来どおり）")
    void toggleOffSkipsCleanup() {
        setToggle(false);
        DiningTable table = table();
        try {
            openAndClose(table);
            assertThat(diningTableRepository.findById(table.getId()).orElseThrow()
                    .isNeedsCleanup())
                    .as("OFF なのに片付け待ちになった").isFalse();
        } finally {
            cleanUp(table);
        }
    }

    @Test
    @DisplayName("★ 片付け待ちの卓には、ご案内（開伝票）が拒否される")
    void guidingIntoDirtyTableIsRejected() {
        setToggle(true);
        DiningTable table = table();
        try {
            openAndClose(table);

            assertThatThrownBy(() -> tableService.openSession(table.getId(), 2))
                    .isInstanceOf(TableService.TableNotReadyException.class)
                    .hasMessageContaining("片付け待ち");
        } finally {
            cleanUp(table);
        }
    }

    @Test
    @DisplayName("★ お客さまが片付け前の卓で QR を読むと、案内ページに乗る")
    void customerQrOnDirtyTableGetsGuidePage() throws Exception {
        // TableNotReadyException を OrderRejectedException の子にしてあるのは、
        // 修正 2/5 で作った customer/table-unavailable にそのまま乗せるため。
        // 500 でも共通エラーページでもなく、お客さま向けの案内で返す
        setToggle(true);
        DiningTable table = table();
        try {
            openAndClose(table);

            mockMvc.perform(post("/t/" + table.getAccessToken() + "/start")
                            .param("guestCount", "2")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/table-unavailable"));
        } finally {
            cleanUp(table);
        }
    }

    @Test
    @DisplayName("会計取消（reopen）で片付け待ちは消える（在席に戻るから）")
    void reopenClearsTheFlag() {
        setToggle(true);
        DiningTable table = table();
        try {
            TableSession bill = openAndClose(table);
            assertThat(diningTableRepository.findById(table.getId()).orElseThrow()
                    .isNeedsCleanup()).isTrue();

            tableService.reopenSession(bill.getId(), "テスト");

            // 旗が残ると「開いている伝票」と「片付け待ち」が同じ卓に同居する
            assertThat(diningTableRepository.findById(table.getId()).orElseThrow()
                    .isNeedsCleanup()).isFalse();
        } finally {
            cleanUp(table);
        }
    }
}
