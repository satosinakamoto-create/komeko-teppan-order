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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品の「カテゴリーから検索」（2026-09-19、設計 ト10 731:4408）。
 *
 * <p>店主の指示「カテゴリ検索ができるようにデザイン変えたから修正して」。
 *
 * <p>設計は「しぼり込み 527x48」を 2 つ、あいだ 42px で横に並べます。
 * 品切れ・残数と食材・在庫にある {@code .catpick} と同じ作りで、
 * JavaScript は使いません（{@code <details>} の開閉はブラウザの機能）。
 *
 * <h2>実装で決めたこと</h2>
 * <ol>
 *   <li><b>探すときはカテゴリを無視する。</b>「たこ焼」と打った人は、
 *       それがどのカテゴリにあるかを覚えていません。覚えていたら
 *       カテゴリのほうから辿ります（品切れ・残数と同じ考え方）</li>
 *   <li><b>件数は全件から数える。</b>タブの件数と同じ理由で、
 *       押す前に「そこに何品あるか」が読めることを優先します</li>
 *   <li><b>並べ替えは出さない。</b>絞っているあいだは、画面に見えている
 *       隣の行が本当の隣とは限りません。「上へ」を押すと隠れている品と
 *       入れ替わり、画面上は何も起きていないように見えます</li>
 *   <li><b>タブのリンクにカテゴリを持たせる。</b>落とすと、タブを押した
 *       瞬間に絞り込みが解けて全件に戻ります</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品のカテゴリーから検索")
class ItemsCategorySearchTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Long yakiId;
    private Long ippinId;

    @BeforeEach
    void setUp() {
        clear();
        Category yaki = categoryRepository.save(new Category("お好み焼き", 1));
        Category ippin = categoryRepository.save(new Category("一品料理", 2));
        yakiId = yaki.getId();
        ippinId = ippin.getId();

        menuItemRepository.save(item("肉玉そば", yaki, 1180));
        menuItemRepository.save(item("ねぎそば", yaki, 1380));
        menuItemRepository.save(item("冷やしトマト", ippin, 600));
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private MenuItem item(String name, Category category, int price) {
        MenuItem m = new MenuItem(category, name, price);
        m.setVisible(true);
        m.setDraft(false);
        return m;
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ カテゴリを選ぶと、その分類の品だけになる")
    void choosingACategoryNarrowsTheList() throws Exception {
        String all = page("/admin/items");
        assertThat(all).contains("肉玉そば").contains("冷やしトマト");

        String only = page("/admin/items?category=" + yakiId);
        assertThat(only).as("同じ分類の品が消えている").contains("肉玉そば").contains("ねぎそば");
        assertThat(only).as("★ 別の分類の品が残っている。絞り込めていない")
                .doesNotContain("冷やしトマト");
    }

    /**
     * ★ 閉じていても、いま何で絞っているかが読めること。
     *
     * <p>選ぶ前は「カテゴリーから検索」、選んだあとはそのカテゴリ名に変わります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 選んだカテゴリ名が、閉じた状態でも出ている")
    void theChosenNameIsVisibleWhenClosed() throws Exception {
        assertThat(page("/admin/items"))
                .as("選ぶ前の文言が設計（カテゴリーから検索）とちがう")
                .contains("カテゴリーから検索");

        String chosen = page("/admin/items?category=" + yakiId);
        int head = chosen.indexOf("catpick__head");
        int list = chosen.indexOf("catpick__list");
        assertThat(head).isGreaterThan(0);
        assertThat(chosen.substring(head, list))
                .as("閉じたときの文言が「カテゴリーから検索」のままで、"
                        + "いま何で絞っているのか画面から読めない")
                .contains("お好み焼き");
    }

    /**
     * ★ 探すときはカテゴリを無視する。
     *
     * <p>語を打った人は、それがどのカテゴリにあるかを覚えていません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 語で探すときはカテゴリを無視する")
    void searchingIgnoresTheCategory() throws Exception {
        // 一品料理を選んだまま「そば」で探しても、そばが出ること
        String html = page("/admin/items?category=" + ippinId + "&q=そば");
        assertThat(html).as("カテゴリが効いたままで、探した語の品が出ない")
                .contains("肉玉そば");
    }

    /**
     * ★ 商品一覧には並べ替えのつまみを置かない（2026-09-20、店主の判断）。
     *
     * <p>「ユーザーはドラック＆ドロップで商品は並び変えることないと思うし」。
     * 置き場はカテゴリ編集へ移しました——あちらは 1 カテゴリぶんしか出さないので、
     * <b>表に見えている行がそのまま並び順の全体</b>になり、
     * 落とした位置の意味が決まります。
     *
     * <p><b>この画面では最後まで決まりませんでした。</b>
     * 2026-09-19 に「絞っていても並べ替えられる」へ変えていますが、
     * 全カテゴリが混ざる表では隣の行が同じカテゴリとは限らず、
     * さらに 2026-09-20 に並べ替え（原価率順など）を足したことで
     * 「原価率順に並んだ表で 3 番目に落としたら何番目か」が決まらなくなりました。
     *
     * <p>代わりにこの画面が持つのは<b>見え方だけの並べ替え</b>です
     * （{@code ItemSortTest}）。お客さまの順番は動きません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品一覧に並べ替えのつまみは無い（カテゴリ編集へ移した）")
    void reorderWorksEvenWhileFiltered() throws Exception {
        for (String url : new String[]{
                "/admin/items", "/admin/items?category=" + yakiId,
                "/admin/items?tab=published", "/admin/items?tab=draft"}) {
            String html = page(url);
            String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));
            for (String mark : new String[]{"data-reorder", "dragdot", "reorder-form"}) {
                assertThat(main).as("★ " + url + " に " + mark + " が残っている")
                        .doesNotContain(mark);
            }
        }
    }

    /** ★ 行が 1 つも無いときは出さない（動かしようがない）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 行が無いときは並べ替えを出さない")
    void noRowsMeansNoReorder() throws Exception {
        assertThat(page("/admin/items?q=" + "存在しない商品名ZZZ"))
                .as("行が 1 つも無いのに並べ替えが出ている")
                .doesNotContain("data-reorder");
    }

    /**
     * ★ タブを押しても絞り込みが解けないこと。
     *
     * <p>リンクから category を落とすと、押した瞬間に全件へ戻ります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ タブのリンクにカテゴリが乗っている")
    void theTabsKeepTheCategory() throws Exception {
        String html = page("/admin/items?category=" + yakiId);
        int tabs = html.indexOf("tabtable__tabs");
        assertThat(tabs).isGreaterThan(0);
        assertThat(html.substring(tabs, tabs + 1200))
                .as("タブのリンクにカテゴリが乗っていない。押した瞬間に全件へ戻る")
                .contains("category=" + yakiId);
    }

    /**
     * ★ 「すべてのカテゴリ」に戻る道があること。
     *
     * <p>無いと、一度選んだあと全件へ戻すのにブラウザの戻るか URL を消すしかなくなります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 全件へ戻る道がある")
    void thereIsAWayBackToEverything() throws Exception {
        assertThat(page("/admin/items?category=" + yakiId))
                .as("全件へ戻る行が無い。ブラウザの戻るしか手が無くなる")
                .contains("すべてのカテゴリ");
    }

    /**
     * ★ 探す欄とカテゴリが横に並んでいること（設計 ト10「Frame 4」）。
     *
     * <p>設計は 527x48 を 2 つ・あいだ 42px。ただし設計の箱は右端が 1384 で、
     * 見出しと表（どちらも 1408）より 24px 短かったので、
     * 実装は 1120 いっぱいに伸ばしてそろえています（Figma 側も直しました）。
     */
    @Test
    @DisplayName("★ 探す欄とカテゴリは横 2 列・あいだ 42px")
    void theTwoBoxesSitSideBySide() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"))
                .replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\\s+", "");

        assertThat(css).as(".itemfind が無い").contains(".itemfind{");
        assertThat(css).as("2 列になっていない").contains("grid-template-columns:1fr1fr");
        assertThat(css).as("あいだが設計の 42px でない").contains("gap:42px");
        // .searchbox の margin-top:48 が grid の中で効くと、探す欄だけ 48px 下にずれる
        assertThat(css).as(".itemfind の中の探す欄の上マージンを消していない。"
                + "探す欄だけ 48px 下にずれる")
                .contains(".itemfind.searchbox{margin-top:0;}");
    }
}
