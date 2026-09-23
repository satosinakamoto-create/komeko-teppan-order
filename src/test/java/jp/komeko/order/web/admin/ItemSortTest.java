package jp.komeko.order.web.admin;

import jp.komeko.order.domain.MenuItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品一覧に「並べ替え」を足し、ドラッグのつまみを外す（2026-09-20、店主の判断）。
 *
 * <p>店主の言葉：「編集中の隣に並び順を原価率の高い低いとか Windows フォルダの
 * 並び変えみたいなのを実装仕様と思うんだよね、ユーザーはドラック＆ドロップで
 * 商品は並び変えることないと思うし」
 *
 * <p><b>★ ここでいちばん大事なこと。「並び順」と「並べ替え」は別物です。</b>
 * <pre>
 *   並び順（sortOrder）… DB に保存される値。<b>お客さまのメニューに出る順番</b>
 *                        order by m.category.sortOrder, m.sortOrder, m.id
 *   並べ替え（sort）   … 見え方だけ。店主が今この表をどう見たいか
 * </pre>
 * Windows のフォルダの並べ替えと同じで、<b>押してもお客さまの順番は変わりません</b>。
 * 混ぜると「原価率順に並べたらメニューまで原価率順になった」という事故になります。
 *
 * <p><b>つまみを外した代わり</b>、お客さまの順番は商品の編集画面にある
 * 「並び」の数字欄から変えます（{@code AdminMenuItemController#applyForm} が保存）。
 * つまみより手間ですが、手段が消えるわけではありません。
 *
 * <p>設計は倉庫の「試作A／試作B」（1281:14884 / 1281:15065）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品一覧の並べ替え")
class ItemSortTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private jp.komeko.order.repository.MenuItemRepository menuItemRepository;
    @Autowired
    private jp.komeko.order.repository.CategoryRepository categoryRepository;
    @Autowired
    private jp.komeko.order.service.MenuService menuService;

    /**
     * ★ 商品が 0 件だと、並びの検査が中身ゼロで素通りします。
     *
     * <p>CLAUDE.md の「0 件で素通り」の罠です（卓の分割テストでも 1 度踏みました）。
     * 値段の違う品を 3 つ用意してから見ます。
     */
    @org.junit.jupiter.api.BeforeEach
    void ensureItems() {
        if (menuItemRepository.count() >= 3) {
            return;
        }
        var category = categoryRepository.findAll().stream().findFirst()
                .orElseGet(() -> categoryRepository.save(
                        new jp.komeko.order.domain.Category("並べ替えテスト", 10)));
        int[] prices = {1800, 600, 1200};
        for (int i = 0; i < prices.length; i++) {
            var item = menuService.createDraftItem(category.getId(), "並べ替えテスト" + i);
            item.setPrice(prices[i]);
            item.setDraft(false);
            item.setVisible(true);
            menuItemRepository.save(item);
        }
    }

    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/items.html");

    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @SuppressWarnings("unchecked")
    private List<MenuItem> rowsFor(String query) throws Exception {
        MvcResult r = mockMvc.perform(get("/admin/items" + query))
                .andExpect(status().isOk())
                .andReturn();
        Object rows = r.getModelAndView().getModel().get("rows");
        assertThat(rows).as("rows がモデルに無い").isInstanceOf(List.class);
        return (List<MenuItem>) rows;
    }

    /**
     * ★ 価格の高い順にすると、実際に高い順に並ぶこと。
     *
     * <p>原価率ではなく価格で見るのは、<b>価格はどの品にも必ずある</b>からです。
     * 原価はレシピ未登録だと null で、テストのデータ次第で 0 件になり得ます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 価格が高い順で並ぶ")
    void sortsByPriceDescending() throws Exception {
        List<MenuItem> rows = rowsFor("?sort=price-desc");
        assertThat(rows).as("商品が 0 件で素通りしている").isNotEmpty();

        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i - 1).getPrice())
                    .as("%d 番目と %d 番目が高い順になっていない", i, i + 1)
                    .isGreaterThanOrEqualTo(rows.get(i).getPrice());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 価格が安い順で並ぶ")
    void sortsByPriceAscending() throws Exception {
        List<MenuItem> rows = rowsFor("?sort=price-asc");
        assertThat(rows).isNotEmpty();
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i - 1).getPrice())
                    .isLessThanOrEqualTo(rows.get(i).getPrice());
        }
    }

    /**
     * ★ 並べ替えても、お客さまの順番（sortOrder）は 1 つも動かないこと。
     *
     * <p>この案でいちばん怖いのがここです。見え方を変えるつもりの操作で
     * 保存値が動くと、<b>お客さまのメニューの並びが勝手に変わります</b>。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 並べ替えてもお客さまの順番（sortOrder）は変わらない")
    void sortingNeverTouchesTheCustomerOrder() throws Exception {
        List<MenuItem> before = rowsFor("");
        var snapshot = before.stream()
                .collect(java.util.stream.Collectors.toMap(MenuItem::getId, MenuItem::getSortOrder));

        rowsFor("?sort=cost-desc");
        rowsFor("?sort=price-asc");
        rowsFor("?sort=name");

        List<MenuItem> after = rowsFor("");
        for (MenuItem item : after) {
            assertThat(item.getSortOrder())
                    .as("★ 「%s」の並び順が動いた。並べ替えは見え方だけのはず", item.getName())
                    .isEqualTo(snapshot.get(item.getId()));
        }
    }

    /** ★ 既定（指定なし）は「標準」＝お客さまに出る順のまま。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 指定が無いときは標準（お客さまに出る順）")
    void defaultKeepsTheCustomerOrder() throws Exception {
        List<MenuItem> plain = rowsFor("");
        List<MenuItem> explicit = rowsFor("?sort=default");
        assertThat(explicit.stream().map(MenuItem::getId).toList())
                .as("既定と「標準」で並びが違う")
                .isEqualTo(plain.stream().map(MenuItem::getId).toList());
    }

    /** ★ 選択肢が画面に出ていること。 */
    @Test
    @DisplayName("★ 並べ替えの選択肢が画面にある")
    void theSortMenuIsOnTheScreen() throws Exception {
        String html = tpl();
        assertThat(html).as("並べ替えの入口が無い").contains("sortOptions");
        assertThat(html).as("いま何で並べているかが出ていない").contains("currentSort");
    }

    /**
     * ★ 並びの列（ドラッグのつまみ）は出さない。
     *
     * <p>外した理由は 2 つです。
     * <ul>
     *   <li>店主の「ユーザーはドラック＆ドロップで商品は並び変えることない」</li>
     *   <li>並べ替えた状態でドラッグさせると、<b>落とした位置の意味が決まらない</b>。
     *       原価率順に並んだ表で 3 番目に落としても、お客さまの順番の
     *       何番目なのか対応が付きません</li>
     * </ul>
     * 空いた 118px は 6 列に配り直しています（商品名 276 → 314px）。
     */
    @Test
    @DisplayName("★ 並びの列（つまみ）は出さない")
    void theDragHandleColumnIsGone() throws Exception {
        String html = tpl();
        assertThat(html).as("★ つまみが残っている").doesNotContain("data-reorder-handle");
        assertThat(html).as("★ 並びの列が残っている").doesNotContain("col-order");
    }

    /**
     * ★ 並べ替えても、押した場所（タブ・検索語・カテゴリ）を失わないこと。
     *
     * <p>失うと、絞り込んでから並べ替えた瞬間に全件へ戻ります。
     */
    @Test
    @DisplayName("★ 並べ替えの行き先がタブと検索語を持っている")
    void theSortLinksCarryTheCurrentFilters() throws Exception {
        String html = tpl();
        int at = html.indexOf("sortOptions");
        assertThat(at).isGreaterThan(0);
        String around = html.substring(Math.max(0, at - 600),
                Math.min(html.length(), at + 900));
        assertThat(around).as("タブを持っていない").contains("currentTab");
        assertThat(around).as("検索語を持っていない").contains("${q}");
    }
}
