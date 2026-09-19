package jp.komeko.order.web.admin;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * カテゴリを「読む画面」と「直す画面」に分けた（2026-09-07 / 設計 09 カテゴリ 41:1782）。
 *
 * <p><b>なぜ分けたか</b><br>
 * それまでは {@code /admin/categories} を開いた瞬間に新規追加フォームが出て、
 * その下に 1 行ずつ入力欄の付いた表が続いていました。
 * ところがこの画面を開く理由のほとんどは
 * 「いまカテゴリが何個あって、どれに何品入っているか」を見ることで、
 * 直すのはたまにです。
 * 見るために開いたのに、いきなり書き換えられる画面が出るのは、
 * <b>押し間違いの的を常に置いてある</b>のと同じでした。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("カテゴリの一覧と編集の分離")
class CategoryScreenSplitTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path LIST =
            Path.of("src/main/resources/templates/admin/category-list.html");
    private static final Path EDIT =
            Path.of("src/main/resources/templates/admin/categories.html");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 開いた直後は読むだけ（入力欄も追加フォームも出さない）")
    void listHasNoForm() throws Exception {
        String html = mockMvc.perform(get("/admin/categories"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/category-list"))
                .andReturn().getResponse().getContentAsString();

        // 本文（サイドバーや上の帯は共通レイアウトなので、そちらの form は数えない）
        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

        // ★ 2026-09-19：並べ替えだけは<b>例外</b>にしました（店主の指示）。
        //   サイドバーから来るのはこの画面なので、ここで並べたい、という理由です。
        //
        //   この決まりが守りたいのは「<b>見ているだけのつもりが、押し間違いで
        //   書き換わる</b>」を防ぐこと。的になるのは入力欄と保存ボタンで、
        //   つまみは掴んで動かす 2 段階の操作なので、その的にはなりません。
        //   実際、入力欄は 1 つも増えていません（隠しフォームの中身は hidden だけ）。
        //
        //   なので見張る相手を変えます：
        //     ・目に見える入力欄が無いこと（hidden は数えない）
        //     ・並べ替え以外の form が無いこと
        String visible = main.replaceAll("(?s)<form id=\"reorder-form\".*?</form>", "");
        assertThat(visible).as("読む画面に目に見える入力欄が残っている")
                .doesNotContain("<input");

        // ★ 文言ではなく実体で見ること。
        //   「カテゴリを追加」という語は、1 件も無いときの案内リンクにも出る。
        //   語で判定すると、その案内があるだけで落ちる（実際に落とした）
        assertThat(visible).as("読む画面から書き換えられる（並べ替え以外の form がある）")
                .doesNotContain("method=\"post\"");

        // 直す口はボタン 1 つだけ
        assertThat(main).contains("/admin/categories/edit");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 直す画面には一覧へ戻る口がある")
    void editHasAWayBack() throws Exception {
        String html = mockMvc.perform(get("/admin/categories/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/categories"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("カテゴリを追加")))
                .andReturn().getResponse().getContentAsString();

        // 戻り口が無いと、直しに来ただけの人が終わったあとに道を探すことになる
        assertThat(html).as("一覧へ戻る口が無い").contains("← カテゴリ一覧へ");
    }

    @Test
    @DisplayName("★ 保存したあとは直す画面に留まる（一覧へ飛ばさない）")
    void savingStaysOnTheEditScreen() throws Exception {
        // 続けて何個も直したいので、1 個保存するたびに読む画面へ戻されると作業にならない
        // ★ 変数名を java にしないこと。java.util.regex… がこの変数に解決され、
        //   「変数 util が見つかりません」という分かりにくい形で落ちます。
        String source = Files.readString(
                Path.of("src/main/java/jp/komeko/order/web/admin/AdminCategoryController.java"));

        // ★ 2026-09-19：戻り先の「数」を数えるのをやめました。
        //   もとは redirect:/admin/categories/edit がちょうど 7 個、と書いてあり、
        //   POST の口を 1 つ足すたびに落ちました（並べ替えの /place を足して実際に落ちた）。
        //   守りたいのは「保存したあと読む画面へ飛ばさないこと」で、口の数ではありません。
        //
        //   カテゴリへ戻る redirect を全部拾って、どれも /edit で終わることを見ます。
        //
        // ★ 2026-09-19 夕：並べ替え（/place）だけは対象外にしました。
        //   店主の指示で並べ替えが一覧だけになり、/place の戻り先を
        //   /admin/categories（一覧）に変えたためです。
        //   ここが守りたいのは「保存のたびに読む画面へ飛ばされて作業が続かない」ことで、
        //   並べ替えは保存ではありません。つまんだ画面にそのまま戻るのが正しく、
        //   編集画面へ飛ばすと、続けて動かそうとした人がつまみを探すことになります。
        //   戻り先そのものは ReorderStaysOnTheListTest が見張っています。
        String withoutPlace = source.replaceAll("(?s)@PostMapping\\(\"/place\"\\).*?\\n    \\}", "");
        assertThat(withoutPlace)
                .as("/place の口が見つからず、切り出しが効いていない。"
                        + "口の形を変えたなら、この切り出しも直すこと")
                .hasSizeLessThan(source.length());

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"redirect:/admin/categories([^\"]*)\"")
                .matcher(withoutPlace);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(m.group(1))
                    .as("保存後に読む画面へ戻る口がある（" + m.group(0) + "）。"
                            + "続けて何個も直したいので、1 個ごとに読む画面へ戻されると作業にならない")
                    .isEqualTo("/edit");
        }
        assertThat(found).as("カテゴリへ戻る redirect が 1 つも無い").isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 列幅は設計どおり 400 / 200 / 160 / 360（合計 1120）")
    void columnWidths() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".table--cats th:nth-child(1), .table--cats td:nth-child(1) { width: 400px; }");
        assertThat(css).contains(".table--cats th:nth-child(2), .table--cats td:nth-child(2) { width: 200px; }");
        // ★ 2026-09-19：並び列を右端へ移したので 3 番目と 4 番目が入れ替わりました
        //   （店主の指示）。幅の組み合わせ（400/200/360/160＝1120）は同じです。
        assertThat(css).contains(".table--cats th:nth-child(3), .table--cats td:nth-child(3) { width: 360px; }");
        assertThat(css).contains(".table--cats th:nth-child(4), .table--cats td:nth-child(4) { width: 160px; }");
        // 幅を効かせるには table-layout: fixed が要る（auto だと中身の長さで決まる）
        assertThat(css).contains(".table--cats { table-layout: fixed; }");
    }

    @Test
    @DisplayName("★ 見出しと表のあいだは 48px（設計 本文 gap-48）")
    void headingToTableGap() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        // ★ .page-head 側に margin-bottom を持たせないこと。
        //   .page-head は仕入れ・経費でも使っていて、あちらの間はまだ設計を見ていない
        assertThat(css).contains(".page-head + .table-wrap { margin-top: 48px; }");
    }

    @Test
    @DisplayName("カテゴリを作る案内は、直す画面へ連れて行く")
    void createLinksPointAtTheEditScreen() throws Exception {
        // 「先にカテゴリを作ってください」で読む画面に着くと、そこからもう一手要る
        for (Path p : new Path[]{
                Path.of("src/main/resources/templates/admin/items.html"),
                Path.of("src/main/resources/templates/admin/item-form.html")}) {
            assertThat(Files.readString(p))
                    .as(p + " の案内が読む画面を指している")
                    .contains("@{/admin/categories/edit}\">カテゴリ</a> を作ってください");
        }
    }
}
