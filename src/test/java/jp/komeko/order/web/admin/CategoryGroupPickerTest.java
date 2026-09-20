package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.service.MenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大分類は打ち込まず、選ぶ（2026-09-20、店主の指摘
 * 「カテゴリー編集は名前入力じゃなくて既存の中から選択する方式じゃないとおかしくね？」）。
 *
 * <h2>なぜ自由入力をやめたか</h2>
 *
 * <p>{@code groupName} は {@code Category.getTabName()} を通って、
 * <b>お客さまのメニューのタブ名そのもの</b>になります。さらに
 * {@code MenuController.DRINK_SECTION}（"ドリンク"）との完全一致で、
 * 商品ページの見せ方が切り替わります。
 *
 * <pre>
 *   「お食事」と「お食亊」 → タブが 2 つに割れる
 *   「ドリンク」を打ち間違え → 飲み物用の並べ方が黙って効かなくなる
 * </pre>
 *
 * <p>どちらも例外を出さず、お客さまの画面を見るまで気づけません。
 * {@code MenuController} 自身が「店長がこの名前を変えるとドリンクのタブが空になります」と
 * 書き残しています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("大分類は選ぶ方式")
class CategoryGroupPickerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MenuService menuService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DiningTableRepository tableRepository;
    @Autowired
    private jp.komeko.order.repository.TableSessionRepository tableSessionRepository;

    @BeforeEach
    void setUp() {
        clear();
        if (tableRepository.count() == 0) {
            tableRepository.save(new DiningTable("確認用の卓", 4, 10));
        }
        Category a = new Category("広島風お好み焼き", 10);
        a.setGroupName("お食事");
        categoryRepository.save(a);
        Category b = new Category("鉄板麺", 20);
        b.setGroupName("お食事");            // 同じ大分類。重複して並べないこと
        categoryRepository.save(b);
        Category c = new Category("生ビール", 30);
        c.setGroupName("ドリンク");
        categoryRepository.save(c);
        categoryRepository.save(new Category("大分類なし", 40));   // null のまま
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    /**
     * ★ 来店セッションも片付けること。
     *
     * <p>お客さまのメニューを見るために {@code /t/{token}/start} を通すので、
     * {@code table_session} に行ができます。残したまま終わると、
     * <b>あとから走るテストの {@code tableRepository.deleteAll()} が外部キー違反で落ちます。</b>
     * 落ちるのは別のクラスなので、原因を取り違えます（実際に 13 件落としました）。
     */
    private void clear() {
        menuItemRepository.deleteAll();
        tableSessionRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    /** ★ 重複を落として並べること。 */
    @Test
    @DisplayName("★ 大分類は重複なしで並ぶ")
    void groupNamesAreDistinct() {
        assertThat(menuService.groupNames())
                .as("★ 重複が落ちていない／並び順がカテゴリの並びと違う")
                .containsExactly("お食事", "ドリンク");
    }

    /**
     * ★ 大分類が未設定のカテゴリで、空や名前が混ざらないこと。
     *
     * <p>{@code getTabName()} を使うと、未設定のときカテゴリ名が返るので
     * 「大分類なし」がタブの候補として並びます。
     */
    @Test
    @DisplayName("★ 未設定のカテゴリから候補が生えない")
    void categoriesWithoutAGroupDoNotAddOne() {
        assertThat(menuService.groupNames())
                .as("★ getTabName() を使っている。カテゴリ名がタブの候補に並ぶ")
                .doesNotContain("大分類なし", "");
    }

    /** ★ 追加画面に、選択肢と「＋ 新しい大分類を作る」が出ること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 追加画面に選択肢と「＋ 新しい大分類を作る」が出る")
    void theNewScreenOffersThePicker() throws Exception {
        String html = mockMvc.perform(get("/admin/categories/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

        assertThat(main).as("★ 選ぶ欄になっていない").contains("<select");
        assertThat(main).as("いまある大分類が出ていない").contains("お食事").contains("ドリンク");
        assertThat(main).as("★ 新しく作る口が無い。新しい区分を作れなくなる")
                .contains("＋ 新しい大分類を作る");
        assertThat(main).as("番兵の値が出ていない").contains("__new__");
    }

    /** ★ 既存の大分類を選んで作れること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 既存の大分類を選んで作れる")
    void anExistingGroupCanBeChosen() throws Exception {
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "鉄板デザート")
                        .param("groupName", "お食事"))
                .andExpect(status().is3xxRedirection());

        assertThat(categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("鉄板デザート"))
                .findFirst().orElseThrow().getGroupName())
                .isEqualTo("お食事");
    }

    /**
     * ★ 番兵がそのまま保存されないこと。
     *
     * <p>{@code __new__} は {@code @Size(max=20)} を通るので、
     * <b>例外も検証エラーも出ません</b>。
     * お客さまのメニューに {@code __new__} というタブが出るまで誰も気づけません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 「＋ 新しい大分類を作る」が名前として保存されない")
    void theSentinelIsResolved() throws Exception {
        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "鉄板デザート")
                        .param("groupName", "__new__")
                        .param("newGroupName", "デザート"))
                .andExpect(status().is3xxRedirection());

        Category made = categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("鉄板デザート"))
                .findFirst().orElseThrow();
        assertThat(made.getGroupName())
                .as("★ 番兵がそのまま保存された。メニューに __new__ というタブが出る")
                .isEqualTo("デザート");
    }

    /** ★ 新しく作るを選んだのに名前が空なら、作らずに描き直すこと。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 新しい大分類の名前が空なら作らない")
    void anEmptyNewGroupIsRefused() throws Exception {
        long before = categoryRepository.count();

        mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "鉄板デザート")
                        .param("groupName", "__new__")
                        .param("newGroupName", ""))
                .andExpect(status().isOk());

        assertThat(categoryRepository.count())
                .as("★ 名前が空なのに作ってしまった").isEqualTo(before);
    }

    /**
     * ★ 検証エラーで戻ったとき、選択肢が空にならないこと。
     *
     * <p>詰め直しを忘れると、戻ってきた画面の {@code <select>} が空になり、
     * もう一度やり直せません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ エラーで戻っても選択肢が残っている")
    void thePickerSurvivesAValidationError() throws Exception {
        String html = mockMvc.perform(post("/admin/categories").with(csrf())
                        .param("name", "")                 // 必須
                        .param("groupName", "お食事"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("★ 戻った画面の選択肢が空。もう一度やり直せない")
                .contains("お食事").contains("ドリンク");
    }

    // ------------------------------------------------------------------

    /**
     * ★ 名前だけで足した商品が、お客さまのメニューに出ないこと。
     *
     * <p><b>2026-09-07 に実際に出しました。</b>{@code isOrderable()} に
     * {@code !draft} を足しただけで安心し、お客さまのメニューの問い合わせが
     * そこを通っていないことを見落としたためです。
     *
     * <p>だから<b>描いた画面を見ます。</b>Repository と Entity だけ見ていると、
     * 同じ型の穴をまた見落とします。
     */
    @Test
    @DisplayName("★ 書きかけがお客さまのメニューに 1 文字も出ない")
    void draftsNeverReachTheCustomerMenu() throws Exception {
        Category c = categoryRepository.findAll().stream()
                .filter(x -> "広島風お好み焼き".equals(x.getName())).findFirst().orElseThrow();
        // 掲載中の品を 1 つ置いておく（カテゴリごと消えて素通りするのを防ぐ）
        MenuItem shown = new MenuItem(c, "肉玉米粉そば", 1080);
        shown.setVisible(true);
        shown.setDraft(false);
        shown.setSortOrder(10);
        menuItemRepository.save(shown);

        menuService.createDraftItem(c.getId(), "秋の鉄板きのこ");

        // ★ /menu は卓のセッションが要ります（QR から入った状態）。
        //   セッション無しだと中身が空で返り、「何を探しても見つからない」＝
        //   素通りで緑になります。前提の確認がこれを拾いました。
        DiningTable table = tableRepository.findAll().stream().findFirst().orElseThrow();
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/t/" + table.getAccessToken()).session(session));
        mockMvc.perform(post("/t/" + table.getAccessToken() + "/start")
                .session(session).with(csrf()).param("guestCount", "2"));

        String menu = mockMvc.perform(get("/menu").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(menu).as("試験の前提が崩れている（掲載中の品も出ていない）")
                .contains("肉玉米粉そば");
        assertThat(menu)
                .as("★ 書きかけがお客さまのメニューに出た。2026-09-07 と同じ事故")
                .doesNotContain("秋の鉄板きのこ");
    }
}
