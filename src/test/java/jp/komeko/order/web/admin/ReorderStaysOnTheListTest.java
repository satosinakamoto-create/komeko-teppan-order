package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 並べ替えたら、並べ替えた画面に戻ること
 * （2026-09-19、店主の指示「卓は編集する画面で並び替えは出来ない仕様にして」）。
 *
 * <h2>なぜこのテストを作ったか</h2>
 *
 * <p>{@code POST /admin/categories/place} と {@code /admin/tables/place} を
 * <b>HTTP 越しに叩いているテストが 1 本もありませんでした</b>。
 * {@code PlaceCategoryAndTableTest} は Service を直接呼ぶだけなので、
 * コントローラの口やテンプレートの {@code th:action} が消えても全部緑のまま通ります。
 * 編集画面を削る作業で {@code /place} まで巻き添えにしても、誰も気づけません。
 *
 * <h2>戻り先を 2026-09-19 に変えました</h2>
 *
 * <pre>
 *   前  redirect:/admin/categories/edit
 *   後  redirect:/admin/categories        （卓も同じ）
 * </pre>
 *
 * <p>つまみが両方の画面にあった頃の名残です。並べ替えが一覧だけになった今、
 * <b>一覧でつまんだ瞬間に、並べ替えのできない編集画面へ飛ばされます。</b>
 * 動かした結果をその場で見せるのが正しい戻り先です。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("並べ替えたら一覧に戻る")
class ReorderStaysOnTheListTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private DiningTableRepository tableRepository;

    private Long catA, catB;
    private Long tblA, tblB;

    @BeforeEach
    void setUp() {
        clear();
        catA = categoryRepository.save(new Category("A", 10)).getId();
        catB = categoryRepository.save(new Category("B", 20)).getId();
        tblA = tableRepository.save(new DiningTable("卓A", 4, 10)).getId();
        tblB = tableRepository.save(new DiningTable("卓B", 4, 20)).getId();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        tableRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private List<String> catOrder() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(Category::getName).toList();
    }

    private List<String> tblOrder() {
        return tableRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(DiningTable::getName).toList();
    }

    /** ★ カテゴリ：口が生きていて、並びが実際に変わること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ カテゴリ：/place が並びを変える")
    void theCategoryEndpointActuallyMoves() throws Exception {
        mockMvc.perform(post("/admin/categories/place").with(csrf())
                        .param("id", String.valueOf(catA))
                        .param("after", String.valueOf(catB)))
                .andExpect(status().is3xxRedirection());

        assertThat(catOrder())
                .as("★ /place が並びを変えていない。コントローラの口が消えていないか")
                .containsExactly("B", "A");
    }

    /** ★ 卓：口が生きていて、並びが実際に変わること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 卓：/place が並びを変える")
    void theTableEndpointActuallyMoves() throws Exception {
        mockMvc.perform(post("/admin/tables/place").with(csrf())
                        .param("id", String.valueOf(tblA))
                        .param("after", String.valueOf(tblB)))
                .andExpect(status().is3xxRedirection());

        assertThat(tblOrder())
                .as("★ /place が並びを変えていない")
                .containsExactly("卓B", "卓A");
    }

    /**
     * ★ 戻り先は一覧であること。
     *
     * <p>編集画面へ戻すと、つまんだ人が<b>並べ替えのできない画面</b>へ飛ばされます。
     * 続けて 2 つめを動かそうとして、そこにつまみが無いことに気づく形になります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 戻り先は一覧（編集画面へ飛ばさない）")
    void itComesBackToTheList() throws Exception {
        mockMvc.perform(post("/admin/categories/place").with(csrf())
                        .param("id", String.valueOf(catA))
                        .param("after", String.valueOf(catB)))
                .andExpect(redirectedUrl("/admin/categories"));

        mockMvc.perform(post("/admin/tables/place").with(csrf())
                        .param("id", String.valueOf(tblA))
                        .param("after", String.valueOf(tblB)))
                .andExpect(redirectedUrl("/admin/tables"));
    }

    /**
     * ★ 動かせなかったときも一覧に戻ること。
     *
     * <p>行き先が空のときは何もしません。ここで編集画面へ飛ばすと、
     * 「動かなかったうえに画面まで変わった」という分かりにくい結果になります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 動かせなくても一覧に戻る")
    void itComesBackEvenWhenNothingMoved() throws Exception {
        mockMvc.perform(post("/admin/categories/place").with(csrf())
                        .param("id", String.valueOf(catA)))
                .andExpect(redirectedUrl("/admin/categories"));
        assertThat(catOrder()).as("行き先が無いのに動いた").containsExactly("A", "B");

        mockMvc.perform(post("/admin/tables/place").with(csrf())
                        .param("id", String.valueOf(tblA)))
                .andExpect(redirectedUrl("/admin/tables"));
        assertThat(tblOrder()).as("行き先が無いのに動いた").containsExactly("卓A", "卓B");
    }
}
