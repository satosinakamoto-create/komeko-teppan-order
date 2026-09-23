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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * カテゴリの編集画面から商品を出し入れする（2026-09-20）。
 *
 * <h2>なぜ HTTP 越しに叩くのか</h2>
 *
 * <p>{@code CategoryItemMoveTest} はサービスを直接呼んでいます。
 * それだけだと、<b>コントローラの口やテンプレートの送り先が消えても全部緑のまま</b>です。
 * {@code ReorderStaysOnTheListTest} が同じ理由で作られています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("カテゴリの編集画面から商品を出し入れする")
class CategoryItemsHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Long from;
    private Long to;
    private Long yakisoba;

    @BeforeEach
    void setUp() {
        clear();
        Category f = categoryRepository.save(new Category("鉄板おつまみ", 10));
        Category t = categoryRepository.save(new Category("鉄板麺", 20));
        from = f.getId();
        to = t.getId();
        item(f, "牛すじ焼き", 10);
        item(f, "せせり焼き", 20);
        yakisoba = item(t, "焼きそば", 10);
    }

    private Long item(Category c, String name, int sort) {
        MenuItem m = new MenuItem(c, name, 800);
        m.setVisible(true);
        m.setDraft(false);
        m.setSortOrder(sort);
        return menuItemRepository.save(m).getId();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private List<Long> idsIn(Long categoryId) {
        return menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)
                .stream().map(MenuItem::getId).toList();
    }

    // ------------------------------------------------------------------ 移す

    /** ★ 口が生きていて、並びが実際に変わること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 選んだ商品がまとめて移る")
    void selectedItemsMove() throws Exception {
        List<Long> before = idsIn(from);

        mockMvc.perform(post("/admin/categories/{id}/items/move", from).with(csrf())
                        .param("itemIds", String.valueOf(before.get(0)))
                        .param("itemIds", String.valueOf(before.get(1)))
                        .param("targetCategoryId", String.valueOf(to)))
                .andExpect(redirectedUrl("/admin/categories/" + from + "/edit"));

        assertThat(idsIn(from)).as("★ 移っていない").isEmpty();
        assertThat(idsIn(to)).as("★ 行き先の末尾に入っていない")
                .containsExactly(yakisoba, before.get(0), before.get(1));
    }

    /**
     * ★ 1 つも選ばずに押しても、白い画面にしないこと。
     *
     * <p>{@code required = true} だと 400 が出ます。
     * 押した人には「壊れた」としか見えません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 1 つも選ばずに押しても壊れない")
    void nothingSelectedIsHandled() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/items/move", from).with(csrf())
                        .param("targetCategoryId", String.valueOf(to)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/categories/" + from + "/edit"));

        assertThat(idsIn(from)).as("何も選んでいないのに動いた").hasSize(2);
    }

    /** ★ いま開いているカテゴリへは移せないこと（選べても何も起きない操作を通さない）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 自分自身へは移せない")
    void movingToItselfIsRefused() throws Exception {
        List<Long> before = idsIn(from);

        mockMvc.perform(post("/admin/categories/{id}/items/move", from).with(csrf())
                        .param("itemIds", String.valueOf(before.get(0)))
                        .param("targetCategoryId", String.valueOf(from)))
                .andExpect(status().is3xxRedirection());

        assertThat(idsIn(from)).as("★ 自分自身へ移して並びが動いた").isEqualTo(before);
    }

    // ------------------------------------------------------------------ 足す

    /** ★ 名前だけで足せること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 名前だけで書きかけを足せる")
    void addingByNameCreatesADraft() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/items", from).with(csrf())
                        .param("name", "秋の鉄板きのこ"))
                .andExpect(redirectedUrl("/admin/categories/" + from + "/edit"));

        MenuItem made = menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(from)
                .stream().filter(i -> i.getName().equals("秋の鉄板きのこ")).findFirst().orElse(null);
        assertThat(made).as("★ 足せていない").isNotNull();
        assertThat(made.isDraft()).as("書きかけになっていない").isTrue();
        assertThat(made.isVisible()).as("★ 掲載のままになっている").isFalse();
    }

    /**
     * ★ 同名があったら、<b>作らない</b>こと。
     *
     * <p>「エラーを出す」ではなく「増えない」を見ます。
     * エラーを出しつつ作る実装は、画面の上では正しく見えます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 同じ名前を打っても商品は増えない")
    void aDuplicateNameDoesNotCreateAnything() throws Exception {
        long before = menuItemRepository.count();

        mockMvc.perform(post("/admin/categories/{id}/items", from).with(csrf())
                        .param("name", "  焼きそば  "))          // 空白付きでも見つけること
                .andExpect(status().isOk())                       // 3xx ではなく描き直し
                .andExpect(view().name("admin/category-edit"));

        assertThat(menuItemRepository.count())
                .as("★ 同名なのに作ってしまった。気づけるのは商品一覧を見たときになる")
                .isEqualTo(before);
    }

    /** ★ 止めたときは、どこにあるかと［ここへ移す］を出すこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 止めたら「どこにあるか」と移す口を出す")
    void itSaysWhereTheDuplicateIs() throws Exception {
        String html = mockMvc.perform(post("/admin/categories/{id}/items", from).with(csrf())
                        .param("name", "焼きそば"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("どこにあるか出ていない").contains("鉄板麺");
        assertThat(html).as("★ .alert--error で知らせていない").contains("alert--error");
        assertThat(html).as("★［ここへ移す］が無い。止めただけで逃げ道が無い")
                .contains("ここへ移す");
    }

    /** ★［ここへ移す］が実際に効くこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★［ここへ移す］で所属が変わる")
    void theMoveHereButtonWorks() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}/items/move", to).with(csrf())
                        .param("itemIds", String.valueOf(yakisoba))
                        .param("targetCategoryId", String.valueOf(from)))
                .andExpect(status().is3xxRedirection());

        assertThat(idsIn(from)).as("★ 移っていない").contains(yakisoba);
        assertThat(idsIn(from).get(idsIn(from).size() - 1))
                .as("行き先の末尾に入っていない").isEqualTo(yakisoba);
    }

    // ------------------------------------------------------------------ 表示

    /** ★ 一覧の札で表示／非表示が切り替わること（編集画面から外した唯一の口）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧の札で表示／非表示が切り替わる")
    void theBadgeTogglesVisibility() throws Exception {
        assertThat(categoryRepository.findById(from).orElseThrow().isVisible()).isTrue();

        mockMvc.perform(post("/admin/categories/{id}/visibility", from).with(csrf()))
                .andExpect(redirectedUrl("/admin/categories"));
        assertThat(categoryRepository.findById(from).orElseThrow().isVisible())
                .as("★ 切り替わっていない。編集画面から外したので、ここが唯一の口").isFalse();

        mockMvc.perform(post("/admin/categories/{id}/visibility", from).with(csrf()));
        assertThat(categoryRepository.findById(from).orElseThrow().isVisible())
                .as("★ 戻せない。片道になっている").isTrue();
    }

    /**
     * ★ 大分類は編集画面から直せること（2026-09-20 夕に開き直した）。
     *
     * <p>いったん「変えられない」にしましたが、同じ日に自由入力をやめて
     * 選ぶ方式にしたので、閉じる理由（打ち間違い）のほうが先に消えていました。
     * 閉じたままだと、すでに入っている変な値を直す手段がどこにも無くなります。
     *
     * <p><b>ただし表示／非表示は今も送れません。</b>切り替え口は一覧の札だけです。
     * 送られてこない項目を写すと、Form の初期値 true が書かれ、
     * 隠したカテゴリが名前を直しただけで表に戻ります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 大分類は直せる／表示は送りつけても変わらない")
    void theGroupNameCanBeFixedButVisibilityCannot() throws Exception {
        Category c = categoryRepository.findById(from).orElseThrow();
        c.setGroupName("お好み焼き");     // カテゴリ名とほぼ同じ、直したい値
        c.setVisible(false);
        categoryRepository.save(c);

        mockMvc.perform(post("/admin/categories/{id}", from).with(csrf())
                        .param("name", "鉄板おつまみ")
                        .param("groupName", "鉄板料理")
                        .param("visible", "true"))     // ★ 送っても効かないこと
                .andExpect(status().is3xxRedirection());

        Category after = categoryRepository.findById(from).orElseThrow();
        assertThat(after.getGroupName())
                .as("★ 大分類を直せない。変な値が入ったままになる")
                .isEqualTo("鉄板料理");
        assertThat(after.isVisible())
                .as("★ 表示／非表示まで書き換わった。切り替え口は一覧の札だけのはず")
                .isFalse();
    }

    /** ★ 他のカテゴリの商品が混ざらないこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 編集画面に他のカテゴリの商品が出ない")
    void onlyItsOwnItemsAreShown() throws Exception {
        String html = mockMvc.perform(get("/admin/categories/{id}/edit", from))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

        assertThat(main).as("自分の商品が出ていない").contains("牛すじ焼き");
        assertThat(main)
                .as("★ 別のカテゴリの商品が混ざっている。"
                        + "findAll を書いて絞り忘れると、件数が多いだけに見えて気づけない")
                .doesNotContain("焼きそば");
    }

    /** ★ 知らない id で開いても 500 にしないこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 知らない id で開いたら一覧へ戻す")
    void anUnknownIdGoesBackToTheList() throws Exception {
        mockMvc.perform(get("/admin/categories/{id}/edit", 999999L))
                .andExpect(redirectedUrl("/admin/categories"));
    }
}
