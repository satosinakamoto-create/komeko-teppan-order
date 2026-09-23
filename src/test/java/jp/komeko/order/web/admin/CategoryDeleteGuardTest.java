package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品が入っているカテゴリは消せない（2026-09-20）。
 *
 * <h2>なぜ今このテストを書くか</h2>
 *
 * <p>この決まりを見張るテストが<b>1 本もありませんでした</b>。
 * 実装は {@code AdminCategoryController} の中にありますが、無防備です。
 *
 * <p>これからカテゴリ画面を 3 枚に分けます。その作業で
 * {@code delete} の戻り先を書き換える——つまり<b>この門のすぐ隣に指を入れる</b>ので、
 * 先に固定しておきます。
 *
 * <h2>なぜ画面のボタンだけでは足りないか</h2>
 *
 * <p>画面の削除ボタンは品数 &gt; 0 のとき {@code disabled} になりますが、
 * それは<b>見た目の話</b>です。URL を直に叩けば通ってしまいます。
 * 商品は所属カテゴリが必須（{@code category_id} は NOT NULL）なので、
 * 親が消えると行き場の無い商品が残ります。
 *
 * <h2>★ 戻り先は見ないこと</h2>
 *
 * <p>このあとの作業で redirect 先が変わります。ここで守りたいのは
 * <b>「消えないこと」</b>であって行き先ではありません。
 * 行き先は別のテストが見ます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品が入っているカテゴリは消せない")
class CategoryDeleteGuardTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Long withItems;
    private Long empty;

    @BeforeEach
    void setUp() {
        clear();
        Category c = categoryRepository.save(new Category("鉄板おつまみ", 10));
        withItems = c.getId();
        MenuItem m = new MenuItem(c, "牛すじ焼き", 980);
        m.setVisible(true);
        m.setDraft(false);
        menuItemRepository.save(m);

        empty = categoryRepository.save(new Category("空っぽ", 20)).getId();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @SuppressWarnings("unchecked")
    private List<String> flashErrorsOf(MvcResult result) {
        Object v = result.getFlashMap().get("flashErrors");
        return v == null ? List.of() : (List<String>) v;
    }

    /** ★ 商品が入っていたら、URL を直に叩いても消えないこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品が入っているカテゴリは残る")
    void theCategorySurvives() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/delete", withItems).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(categoryRepository.findById(withItems))
                .as("★ 商品が入っているのにカテゴリが消えた。"
                        + "画面のボタンを disabled にするだけでは、URL を直に叩かれると通る")
                .isPresent();
    }

    /**
     * ★ 中の商品も無事であること。
     *
     * <p>カテゴリが消えるときに商品まで道連れにする実装へ変えてしまうと、
     * この 1 本だけが気づけます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 中の商品も消えない")
    void theItemsSurvive() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/delete", withItems).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(menuItemRepository.count())
                .as("★ カテゴリと一緒に商品まで消えた")
                .isEqualTo(1);
    }

    /** ★ 断った理由を画面に出すこと（黙って何も起きないのがいちばん困る）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 消せない理由を知らせる")
    void itSaysWhy() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/admin/categories/{id}/delete", withItems).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(flashErrorsOf(result))
                .as("★ 黙って戻している。押したのに何も起きないように見える")
                .isNotEmpty()
                .anySatisfy(msg -> assertThat(msg).contains("削除できません"));
    }

    /** ★ 空のカテゴリは、ちゃんと消せること（門が固すぎないこと）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 空のカテゴリは消せる")
    void anEmptyCategoryCanGo() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/delete", empty).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(categoryRepository.findById(empty))
                .as("★ 商品が 1 件も無いのに消せない。門が固すぎる")
                .isEmpty();
    }
}
