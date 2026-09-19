package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
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
 * 並べ替えの仕掛けを 3 画面で使い回している（2026-09-19、店主の指示
 * 「商品、カテゴリー、卓にもドラッグ＆ドロップ実装してほしい」）。
 *
 * <h2>必要な目印はどれも同じ</h2>
 * <pre>
 *   [data-reorder]          … この入れ物は並べ替えられる
 *   [data-item-id]          … 動かせる行（入れ物の直下）
 *   [data-group-id]         … 同じ group どうしでしか動かせない
 *   [data-reorder-handle]   … つまみ
 *   form#reorder-form       … 送り先（id / before / after）
 * </pre>
 *
 * <p><b>入れ物と行のタグは問いません。</b>商品は {@code <tbody>} / {@code <tr>}、
 * カテゴリと卓は {@code <ul>} / {@code <li>} です。
 * セレクタにタグ名を書くと、片方でしか動きません。
 *
 * <h2>カテゴリと卓は「直す画面」に置いた</h2>
 * <p>{@code /admin/categories} と {@code /admin/tables} は<b>読むだけの画面</b>です
 * （{@code CategoryScreenSplitTest} / {@code TableQrScreenSplitTest} が見張っています）。
 * 一度そちらへ置いて、読む画面に入力欄が入ってしまい落ちました。
 * 並べ替えは直す作業なので、置き場は {@code /edit} です。
 *
 * <h2>ブラウザで実際に動かして確かめたこと</h2>
 * <pre>
 *   商品      <tbody> つまみ 100 個   1 2 3 4 5 → 2 1 3 4 5   id=1 after=2
 *   カテゴリ  <ul>    つまみ  15 個   1 2 3 4 5 → 2 1 3 4 5   id=1 after=2
 *   卓        <ul>    つまみ  10 個   1 2 3 4 5 → 2 1 3 4 5   id=1 after=2
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("並べ替えは 3 画面で共通")
class ReorderIsSharedTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private DiningTableRepository tableRepository;

    /**
     * 3 画面とも<b>行が 1 つも無ければ並べ替えを出しません</b>（動かしようがないため）。
     * 素の試験用 DB は空なので、ここで行を用意します。
     * 用意し忘れると「仕掛けが無い」と落ち、原因を取り違えます（実際に一度落ちました）。
     */
    @BeforeEach
    void setUp() {
        clear();
        Category c = categoryRepository.save(new Category("並べ替え試験", 10));
        categoryRepository.save(new Category("並べ替え試験2", 20));
        for (String name : new String[]{"品A", "品B"}) {
            MenuItem m = new MenuItem(c, name, 1000);
            m.setVisible(true);
            m.setDraft(false);
            menuItemRepository.save(m);
        }
        tableRepository.save(new DiningTable("卓A", 4, 10));
        tableRepository.save(new DiningTable("卓B", 4, 20));
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        tableRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private String page(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** ★ 3 画面とも、必要な目印がそろっていること。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品・カテゴリ・卓のどれにも仕掛けがある")
    void allThreeScreensHaveIt() throws Exception {
        for (String url : new String[]{
                "/admin/items", "/admin/categories/edit", "/admin/tables/edit"}) {
            String html = page(url);
            assertThat(html).as(url + " に並べ替えの入れ物が無い").contains("data-reorder=\"on\"");
            assertThat(html).as(url + " につまみが無い").contains("data-reorder-handle");
            assertThat(html).as(url + " に送り先のフォームが無い").contains("id=\"reorder-form\"");
            assertThat(html).as(url + " のフォームに after が無い。"
                    + "いちばん下へ落としたときに行き先を指せず、先頭へ飛ぶ")
                    .contains("name=\"after\"");
        }
    }

    /**
     * ★ 一覧（サイドバーから来る画面）でも並べ替えられること。
     *
     * <p><b>2026-09-19 に、前の判断をひっくり返しました。</b>
     * はじめは「あの 2 つは読むだけの画面なので置かない」として {@code /edit} だけに
     * 置きましたが、店主が<b>サイドバーから来て並べようとして見つけられません</b>でした。
     * 「カテゴリ、卓でもドラッグ＆ドロップできるようにして」と 2 度言われています。
     *
     * <p>読むだけ、という決まりが守りたいのは
     * 「見ているだけのつもりが、押し間違いで書き換わる」を防ぐことです。
     * 的になるのは入力欄と保存ボタンで、つまみは掴んで動かす 2 段階の操作なので、
     * その的にはなりません。<b>目に見える入力欄は 1 つも増えていません。</b>
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧（サイドバーから来る画面）でも並べ替えられる")
    void theListsCanReorderToo() throws Exception {
        for (String url : new String[]{"/admin/categories", "/admin/tables"}) {
            String html = page(url);
            String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

            assertThat(main).as(url + " に並べ替えが無い。"
                    + "サイドバーから来るのはこの画面なので、ここで並べられないと見つからない")
                    .contains("data-reorder=\"on\"");
            assertThat(main).as(url + " につまみが無い").contains("data-reorder-handle");

            // ★ ただし目に見える入力欄は増やさないこと。
            //   隠しフォームの中身（hidden）だけが例外
            String visible = main.replaceAll("(?s)<form id=\"reorder-form\".*?</form>", "");
            assertThat(visible).as(url + " は読むだけの画面。目に見える入力欄を置かない")
                    .doesNotContain("<input");
        }
    }

    /** ★ 並び列は右端（店主の指示「並び順を一番右に来るようにレイアウト変更もしておいて」）。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 並び列は表のいちばん右")
    void theOrderColumnIsLast() throws Exception {
        for (String url : new String[]{"/admin/categories", "/admin/tables"}) {
            String html = page(url);
            int head = html.indexOf("<thead>");
            int endHead = html.indexOf("</thead>", head);
            String thead = html.substring(head, endHead);

            int order = thead.lastIndexOf(">並び<");
            assertThat(order).as(url + " に並び列が無い").isGreaterThan(0);
            // 並びのあとに列見出しが無いこと＝いちばん右
            assertThat(thead.substring(order))
                    .as(url + " の並び列が右端にない")
                    .doesNotContain("<th");
        }
    }

    /**
     * ★ セレクタにタグ名を書かないこと。
     *
     * <p>商品は {@code <tbody>} / {@code <tr>}、カテゴリと卓は {@code <ul>} / {@code <li>} です。
     * {@code tbody[data-reorder]} と書くと、カテゴリと卓で動きません。
     */
    @Test
    @DisplayName("★ 仕掛けはタグに縛られていない")
    void theScriptIsTagAgnostic() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/reorder.js"))
                .replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");

        assertThat(js).as("入れ物の探し方にタグ名が入っている。"
                + "カテゴリと卓（<ul>）で動かなくなる")
                .doesNotContain("tbody[data-reorder]");
        assertThat(js).as("行の探し方にタグ名が入っている")
                .doesNotContain("tr[data-item-id]");
        assertThat(js).as("つまみから行を遡るのにタグ名を使っている")
                .doesNotContain("closest('tr')");

        assertThat(js).contains("querySelector('[data-reorder]')");
        assertThat(js).contains("':scope > [data-item-id]'");
    }

    /** ★ 並べ替えの本体は 1 か所にまとめてあること（3 つに書き散らさない）。 */
    @Test
    @DisplayName("★ 並べ替えの本体は SortOrderPlacer 1 か所")
    void thePlacingLogicLivesInOnePlace() throws Exception {
        assertThat(Files.exists(Path.of(
                "src/main/java/jp/komeko/order/service/SortOrderPlacer.java")))
                .as("共通の置き場が無い").isTrue();

        for (Path p : new Path[]{
                Path.of("src/main/java/jp/komeko/order/service/MenuService.java"),
                Path.of("src/main/java/jp/komeko/order/service/TableService.java")}) {
            assertThat(Files.readString(p))
                    .as(p + " が共通の置き場を使っていない。同じ処理が増えると片方だけ古くなる")
                    .contains("SortOrderPlacer.place(");
        }
    }
}
