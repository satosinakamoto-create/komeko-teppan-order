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
 * <h2>置き場は一覧。ここは 2 度ひっくり返した</h2>
 *
 * <p>同じ往復をもう一度やらないために、経緯を残します。
 *
 * <ol>
 *   <li><b>はじめ</b>：{@code /admin/categories} と {@code /admin/tables} は
 *       読むだけの画面なので、つまみは {@code /edit} に置いた</li>
 *   <li><b>09-19 昼</b>：店主が<b>サイドバーから来て並べようとして見つけられません</b>でした。
 *       サイドバーが指すのは一覧のほうです。一覧にも置きました</li>
 *   <li><b>09-19 夕（いまここ）</b>：店主の指示
 *       「卓は編集する画面で並び替えは出来ない仕様にして」。カテゴリも同じ扱いに。
 *       <b>編集画面からは、つまみも数字の欄も両方外しました</b></li>
 * </ol>
 *
 * <p>いまの決まりは 1 行で言えます——<b>並べ替えは一覧だけ。編集画面は直すだけ。</b>
 *
 * <p>「読むだけの画面に書き換えの手段を置いてよいのか」への答えは
 * {@code #theListsCanReorderToo} に書いてあります。
 * 目に見える入力欄は 1 つも増えていないので、押し間違いの的にはなりません。
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

    /**
     * ★ 3 画面とも、必要な目印がそろっていること。
     *
     * <p>カテゴリと卓は<b>一覧</b>（サイドバーから来る画面）です。
     * 2026-09-19 に {@code /edit} から差し替えました。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品・カテゴリ・卓のどれにも仕掛けがある")
    void allThreeScreensHaveIt() throws Exception {
        for (String url : new String[]{
                "/admin/items", "/admin/categories", "/admin/tables"}) {
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

            // ★ 送り先まで見ること（2026-09-19 に追加）。
            //   編集画面から隠しフォームを外す作業のついでに、
            //   一覧側まで巻き添えで消すのがいちばんありそうな壊し方です。
            //   つまみだけ残って送り先が無いと、掴んで動かせるのに保存されません
            //   （画面の上では動くので、見ただけでは気づけない）。
            assertThat(main).as(url + " に送り先のフォームが無い。"
                    + "つまみは動くのに保存されない状態になる")
                    .contains("id=\"reorder-form\"");
            assertThat(main).as(url + " のフォームに after が無い。"
                    + "いちばん下へ落としたときに行き先を指せず、先頭へ飛ぶ")
                    .contains("name=\"after\"");
            assertThat(main).as(url + " の送り先が place になっていない")
                    .contains(url + "/place");

            // ★ ただし目に見える入力欄は増やさないこと。
            //   例外は 2 つだけ：並べ替えの隠しフォーム（中身は hidden）と、
            //   カテゴリの状態の札（2026-09-20。form の中身はボタンだけ）。
            //   どちらも入力欄は 1 つも増えていません。
            // ★ 属性の並び順に頼らないこと（Thymeleaf が th:action を置く位置で変わる）
            String visible = main
                    .replaceAll("(?s)<form id=\"reorder-form\".*?</form>", "")
                    .replaceAll("(?s)<form[^>]*/visibility\"[^>]*>.*?</form>", "");
            assertThat(visible).as(url + " は読むだけの画面。目に見える入力欄を置かない")
                    .doesNotContain("<input");
        }
    }

    /**
     * ★ 直す画面には並べ替えを置かないこと（2026-09-19、店主の指示
     * 「卓は編集する画面で並び替えは出来ない仕様にして」。カテゴリも同じ扱い）。
     *
     * <p>つまみだけでなく<b>数字で打ち込む欄も</b>置きません。
     * 欄が残っていると「編集画面では並び替えできない」が成り立たないからです。
     *
     * <h2>★「無いこと」を見る前に「行があること」を確かめる</h2>
     *
     * <p>行が 0 件だと中身が空なだけで、何を探しても見つからず通ってしまいます。
     * このクラス自身が {@code setUp} のコメントで、
     * {@code TableQrScreenSplitTest} も同じ罠を踏んだと書き残しています。
     * ここでは先に「行フォームがある」ことを確かめてから「無い」を見ます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 直す画面には並べ替えが無い（つまみも数字の欄も）")
    void theEditScreensCannotReorder() throws Exception {
        // ★ 2026-09-20：カテゴリの編集画面が /{id}/edit になりました。
        //   setUp が作ったカテゴリの id を使います。
        Long categoryId = categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .get(0).getId();

        for (String url : new String[]{
                "/admin/categories/" + categoryId + "/edit", "/admin/tables/edit"}) {
            String html = page(url);
            String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

            // ① まず中身があることを確かめる。無いと以下が全部素通りする
            assertThat(main).as(url + " に中身が無い。"
                    + "空では「並べ替えが無い」を確かめたことにならない")
                    .contains("name=\"name\"");

            // ② そのうえで、並べ替えの手段がどれも無いこと
            for (String mark : new String[]{
                    "data-reorder", "dragdot", "reorder-form",
                    "data-item-id", "data-group-id"}) {
                assertThat(main).as(url + " に " + mark + " が残っている。"
                        + "並べ替えは一覧だけ、編集画面は直すだけ")
                        .doesNotContain(mark);
            }
            assertThat(main).as(url + " に並び順の入力欄が残っている。"
                    + "数字で打ち込めるなら「並び替えできない」ことにならない")
                    .doesNotContain("name=\"sortOrder\"");
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
