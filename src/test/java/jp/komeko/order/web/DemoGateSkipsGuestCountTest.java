package jp.komeko.order.web;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 見学入口は、人数を聞かずにお品書きまで届くこと（設計 暗31 1597:16742）。
 *
 * <p><b>このテストが守っているもの＝見学者に答えようのない質問をしないこと。</b>
 *
 * <p>人数を選ぶ画面（設計 暗00）は<b>実店舗のための段</b>です。
 * テーブルチャージの計算に要るので店では必ず聞きますが、
 * ポートフォリオから来た人に「何名さまですか」と尋ねても意味がありません。
 * 最初の 1 手が答えようのない質問になります。
 *
 * <p><b>ただし飛ばすのは質問だけで、経路は飛ばしていません。</b>
 * 送り先は実店舗と同じ {@code /t/{token}/start} で、
 * 伝票の開き方もテーブルチャージの計算も本番と同じところを通ります。
 * デモ専用の抜け道を作ると、そこだけ本番と挙動が分かれて、
 * <b>見せている画面が本物だと言えなくなります。</b>
 *
 * <p>{@code @Transactional} を付けていないのは、
 * 押したあとに伝票が本当に開いたかを、別のトランザクションから確かめるためです。
 * 巻き戻さないので、後片付けを自分でします。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.guest-login=true")
@DisplayName("見学入口は人数を聞かない")
class DemoGateSkipsGuestCountTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DiningTableRepository tableRepository;
    @Autowired
    private TableSessionRepository sessionRepository;

    /** フォームの送り先と、隠して送っている人数。 */
    private static final Pattern ACTION =
            Pattern.compile("action=\"(/t/[^\"]+/start)\"");
    private static final Pattern GUESTS =
            Pattern.compile("name=\"guestCount\"[^>]*value=\"(\\d+)\"");

    @BeforeEach
    void clean() {
        sessionRepository.deleteAll();
        tableRepository.deleteAll();
    }

    @Test
    @DisplayName("★★ 入口のボタンを押すと、人数を聞かれずにお品書きへ着く")
    void oneButtonReachesTheMenu() throws Exception {
        DiningTable table = tableRepository.save(new DiningTable("カウンター1", 3, 10));

        String html = mockMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("★★ 人数を選ぶ画面が出ている。見学者には答えようがない")
                .doesNotContain("来店人数");

        Matcher a = ACTION.matcher(html);
        assertThat(a.find()).as("★ 進むボタンの送り先が無い").isTrue();

        Matcher g = GUESTS.matcher(html);
        assertThat(g.find()).as("★ 人数が埋められていない。押しても案内で差し戻される").isTrue();
        assertThat(Integer.parseInt(g.group(1)))
                .as("★ 卓の定員で埋めること")
                .isEqualTo(table.getCapacity());

        // 押す。実店舗とまったく同じ入口へ送る
        mockMvc.perform(post(a.group(1)).with(csrf())
                        .param("guestCount", g.group(1)))
                .andExpect(status().is3xxRedirection());

        // ★ 「押せた」だけでは足りない。伝票が本当に開いたかまで見る。
        //    人数が届いていないと、start は案内を出して同じ画面へ戻すので、
        //    転送されたこと自体は成功しても、お品書きには着かない。
        List<TableSession> opened = sessionRepository.findAll();
        assertThat(opened)
                .as("★★ 伝票が開いていない。お品書きへ着いていない")
                .hasSize(1);
        assertThat(opened.get(0).getGuestCount())
                .as("★ 人数が卓の定員で入っていない。テーブルチャージがずれる")
                .isEqualTo(table.getCapacity());
    }

    @Test
    @DisplayName("★ 飛ばしたのは質問だけ。送り先は実店舗と同じ経路")
    void itDoesNotUseADemoOnlyShortcut() throws Exception {
        tableRepository.save(new DiningTable("カウンター1", 2, 10));

        String html = mockMvc.perform(get("/demo"))
                .andReturn().getResponse().getContentAsString();

        Matcher a = ACTION.matcher(html);
        assertThat(a.find()).isTrue();
        assertThat(a.group(1))
                .as("★★ デモ専用の入口を作っている。そこだけ本番と挙動が分かれる")
                .endsWith("/start");

        // GET で状態が変わらないこと。先読みやプレビューで伝票が開いては困る
        assertThat(html)
                .as("★ リンクで送っている。GET で伝票が開くと、"
                        + "ブラウザの先読みだけで席が埋まる")
                .contains("method=\"post\"");
    }
}
