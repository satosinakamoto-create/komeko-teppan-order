package jp.komeko.order.web.staff;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 番号盤面のお会計待ちの印（卓 3/3。2026-09-07 / 店主と合意済み）。
 *
 * <p><b>塞いだ穴</b><br>
 * お客さまがスマホから「お会計」を頼むと伝票は CLOSING になるが、
 * それが出るのはホール画面だけだった。番号盤面（/staff/order）しか
 * 見ていない店員は気づけず、お客さまはレジ前で待ち続ける。
 *
 * <p><b>印だけ。</b>会計の操作はここからさせない（会計はホール画面の仕事）。
 * 色はホールの「お会計待ち」バッジ（badge--stop）と同じにそろえる——
 * 同じ状態が画面ごとに違う色だと、対応が覚えられない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("番号盤面のお会計待ちの印")
class StaffBoardClosingMarkTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;

    private DiningTable table;

    @AfterEach
    void cleanUp() {
        if (table != null) {
            tableSessionRepository.findAll().stream()
                    .filter(s -> s.getDiningTable() != null
                            && table.getId().equals(s.getDiningTable().getId()))
                    .forEach(s -> tableSessionRepository.deleteById(s.getId()));
            diningTableRepository.deleteById(table.getId());
            table = null;
        }
    }

    private String board() throws Exception {
        return mockMvc.perform(get("/staff/order"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** その卓の札 1 枚ぶんの HTML を切り出す（他の卓の印と混ざらないように）。 */
    private String tileOf(String html, String tableName) {
        int at = html.indexOf(tableName);
        assertThat(at).as("札が出ていない: " + tableName).isGreaterThan(0);
        int start = html.lastIndexOf("<a class=\"seat", at);
        int end = html.indexOf("</a>", at);
        return html.substring(start, end);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ お会計待ちにした卓の札に印が出て、再開すると消える")
    void closingMarkAppearsAndDisappears() throws Exception {
        String name = "印テスト" + (System.nanoTime() % 100000);
        table = diningTableRepository.save(new DiningTable(name, 4, 993));
        TableSession bill = tableService.openSession(table.getId(), 2);

        // ご案内直後：在席だが、お会計待ちの印はまだ無い。
        // 最初から出ていたら、このテストは何も守っていない
        assertThat(tileOf(board(), name)).doesNotContain("お会計待ち");

        // お客さまの「お会計おねがいします」と同じ経路
        tableService.startCheckout(bill.getId());

        String tile = tileOf(board(), name);
        assertThat(tile).as("★ 印の文字").contains("お会計待ち");
        // 色はホールと同じ意味（badge--stop）。画面ごとに色が違うと覚えられない
        assertThat(tile).as("印の見た目の指定").contains("badge badge--stop");
        assertThat(tile).as("札の縁も同じ意味の色に").contains("seat--closing");

        // ご注文再開 → 印が消える
        tableService.resumeOrdering(bill.getId());
        assertThat(tileOf(board(), name)).doesNotContain("お会計待ち");
    }

    @Test
    @DisplayName("会計の操作はこの画面に置かない（印だけ）")
    void noCheckoutControlsOnTheBoard() throws Exception {
        // ここから締められると、金額の読み上げも支払い方法の確認も
        // 飛ばした会計ができてしまう。会計はホール画面の仕事
        String html = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/templates/staff/order-board.html"));
        assertThat(html).doesNotContain("/close");
        assertThat(html).doesNotContain("/checkout");
        assertThat(html).doesNotContain("/resume");
    }
}
