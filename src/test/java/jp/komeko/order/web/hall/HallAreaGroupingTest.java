package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ホール盤面のエリア区切り（卓 2/3。2026-09-07 / 店主と合意済み）。
 *
 * <p><b>何を守っているか</b><br>
 * 卓が増えると、盤面の一覧から目当ての席を探すのに時間がかかる。
 * カウンター・小上がりのような<b>店の言葉のまとまり</b>で区切っておくと、
 * ホール画面と実際の店内が対応して探しやすくなる。
 *
 * <p><b>いちばん大事なのは「使わない店では何も変わらない」こと。</b>
 * 全卓がエリア未設定なら見出しを一切出さず、導入前と同じ見た目にする。
 * 半端に「その他」だけの見出しが出ると、設定していない店の画面が
 * 理由もなく 1 行増えることになる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホール盤面のエリア区切り")
class HallAreaGroupingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;

    private final List<Long> madeTables = new ArrayList<>();

    private DiningTable table(String name, int sortOrder, String area) {
        DiningTable t = tableService.createTable(name, 4, sortOrder, area);
        madeTables.add(t.getId());
        return t;
    }

    @AfterEach
    void cleanUp() {
        for (Long id : madeTables) {
            tableSessionRepository.findAll().stream()
                    .filter(s -> s.getDiningTable() != null
                            && id.equals(s.getDiningTable().getId()))
                    .forEach(s -> tableSessionRepository.deleteById(s.getId()));
            diningTableRepository.deleteById(id);
        }
        madeTables.clear();
    }

    private String board() throws Exception {
        return mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ エリアを設定すると、その見出しの下に卓が出る（未設定は「その他」）")
    void groupsAppearWithHeadings() throws Exception {
        long n = System.nanoTime() % 100000;
        // カウンターは在席（伝票を開く）、小上がりと未設定は空席のまま
        DiningTable counter = table("エリアC" + n, 300, "カウンター");
        table("エリアZ" + n, 310, "小上がり");
        table("エリアN" + n, 320, null);
        tableService.openSession(counter.getId(), 2);

        String html = board();

        // 見出しが出る（在席の伝票＝カウンター、空席＝小上がり／その他）
        assertThat(html).contains("areahead");
        assertThat(html).contains(">カウンター</h3>");
        assertThat(html).contains(">小上がり</h3>");
        assertThat(html).as("未設定の卓が「その他」にまとまっていない")
                .contains(">その他</h3>");

        // 並びは卓の並び順（カウンター → 小上がり）、その他は最後
        int atZashiki = html.indexOf(">小上がり</h3>");
        int atOthers = html.indexOf(">その他</h3>");
        assertThat(atZashiki).isLessThan(atOthers);

        // 卓名が本文に出ていること（見出しだけの空騒ぎでない）
        assertThat(html).contains("エリアC" + n);
        assertThat(html).contains("エリアZ" + n);
        assertThat(html).contains("エリアN" + n);
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 全卓が未設定なら、見出しは一切出ない（導入前と同じ見た目）")
    void noHeadingsWhenNoAreas() throws Exception {
        long n = System.nanoTime() % 100000;
        table("素の卓A" + n, 330, null);
        table("素の卓B" + n, 340, null);

        String html = board();

        // 見出しの器そのものが無いこと。「その他」だけが出るのもダメ
        //（設定していない店の画面が理由もなく 1 行増える）
        assertThat(html).doesNotContain("areahead");
        assertThat(html).doesNotContain(">その他</h3>");
        // 卓は従来どおり出ている
        assertThat(html).contains("素の卓A" + n);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("卓の管理画面にエリアの入力欄がある（新規と行編集の両方）")
    void adminFormHasAreaInputs() throws Exception {
        long n = System.nanoTime() % 100000;
        table("エリア編集" + n, 350, "カウンター");

        String html = mockMvc.perform(get("/admin/tables"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 新規フォーム（th:field）と行編集フォーム（手書き name）の両方
        assertThat(html.split("name=\"area\"", -1).length - 1)
                .as("エリアの入力欄が新規と行編集の両方に無い")
                .isGreaterThanOrEqualTo(2);
        // 設定済みの値が行編集に出ている
        assertThat(html).contains("value=\"カウンター\"");
    }
}
