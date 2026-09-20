package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.repository.CategoryRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
    @Autowired
    private CategoryRepository categoryRepository;

    private static final Path LIST =
            Path.of("src/main/resources/templates/admin/category-list.html");
    private static final Path EDIT =
            Path.of("src/main/resources/templates/admin/category-edit.html");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 開いた直後は読むだけ（入力欄も追加フォームも出さない）")
    void listHasNoForm() throws Exception {
        // ★ 行を 1 つ用意してから見ること。
        //   空のままだと表そのものが出ないので、「入力欄が無い」も「直す口がある」も
        //   確かめたことになりません（0 件で素通りする罠。実際に落とした）。
        Long seeded = categoryRepository.save(new Category("読む画面の確認", 10)).getId();
        String html;
        try {
            html = mockMvc.perform(get("/admin/categories"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/category-list"))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            categoryRepository.deleteById(seeded);
        }

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
        //   ★ 2026-09-20：例外が 2 つになりました。状態の札を押せるようにしたためです。
        //     編集画面から表示/非表示のチェックを外したので、切り替え口はここだけです。
        //     これも「掴んで動かす」ほどではありませんが、押した結果がその場で見える
        //     1 つの操作で、入力欄は増えていません（form の中身はボタンだけ）。
        //
        //   なので見張る相手はこうです：
        //     ・目に見える入力欄が無いこと（hidden と CSRF は数えない）
        //     ・例外の 2 つ以外に form が無いこと
        //
        //   ★ doesNotContain を消して逃げないこと。
        //     消すと「読む画面に入力欄を置かない」歯止めが完全に失われます。
        // ★ 属性の並び順に頼らないこと。Thymeleaf が th:action を置き換える位置で
        //   method と action の順が変わり、厳しく書くと剥がし損ねます（実際に落とした）。
        String visible = main
                .replaceAll("(?s)<form id=\"reorder-form\".*?</form>", "")
                .replaceAll("(?s)<form[^>]*/visibility\"[^>]*>.*?</form>", "");

        assertThat(visible).as("読む画面に目に見える入力欄が残っている")
                .doesNotContain("<input type=\"text\"")
                .doesNotContain("<input type=\"number\"")
                .doesNotContain("<select")
                .doesNotContain("<textarea");

        // ★ 文言ではなく実体で見ること。
        //   「カテゴリを追加」という語は、1 件も無いときの案内リンクにも出る。
        //   語で判定すると、その案内があるだけで落ちる（実際に落とした）
        assertThat(visible).as("読む画面から書き換えられる（例外の 2 つ以外に form がある）")
                .doesNotContain("method=\"post\"");

        // 直す口と足す口
        assertThat(main).as("足す口が無い").contains("/admin/categories/new");
        assertThat(main).as("直す口が無い").contains("/edit");
    }

    /**
     * ★ 直す画面には一覧へ戻る口があること。
     *
     * <p>2026-09-20 に URL が {@code /{id}/edit} へ変わりました。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 直す画面には一覧へ戻る口がある")
    void editHasAWayBack() throws Exception {
        Long id = categoryRepository.save(new Category("戻り口の確認", 10)).getId();
        try {
            String html = mockMvc.perform(get("/admin/categories/{id}/edit", id))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/category-edit"))
                    .andReturn().getResponse().getContentAsString();

            // 戻り口が無いと、直しに来ただけの人が終わったあとに道を探すことになる
            assertThat(html).as("一覧へ戻る口が無い").contains("← カテゴリ一覧へ");
        } finally {
            categoryRepository.deleteById(id);
        }
    }

    /**
     * ★ 廃止した {@code /admin/categories/edit} が生き残っていないこと。
     *
     * <p><b>返るのは 404 ではなく 405 です。</b>実測しました。
     * {@code POST /{id}} があるので、{@code edit} を id として<b>経路だけ</b>は当たり、
     * GET のハンドラが無いので「メソッドが違う」になります。
     * ここで 404 を期待すると、実装が正しいのにテストが落ちます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 古い /admin/categories/edit はもう開けない")
    void theOldEditScreenIsGone() throws Exception {
        mockMvc.perform(get("/admin/categories/edit"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
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
        // ★ 2026-09-20：文字列を総なめする形をやめました。
        //   編集画面が /{id}/edit（連結で組み立てる）になったので、
        //   リテラルを拾う正規表現では group(1) が "/" になって落ちます。
        //   守りたいのは「保存したあと読む画面へ飛ばされない」なので、
        //   実際に POST を投げて redirect 先を見る形にします。
        assertThat(source).as("コントローラが読めていない").isNotEmpty();

        Long id = categoryRepository.save(new Category("戻り先の確認", 10)).getId();
        try {
            // 名前を変えて保存 → その編集画面へ戻ること
            mockMvc.perform(post("/admin/categories/{id}", id).with(csrf())
                            .param("name", "戻り先の確認2"))
                    .andExpect(redirectedUrl("/admin/categories/" + id + "/edit"));

            // 追加 → 作ったカテゴリの編集画面へ着地すること（次は中身を入れるので）
            String created = mockMvc.perform(post("/admin/categories").with(csrf())
                            .param("name", "新しく作った")
                            .param("groupName", ""))
                    .andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            assertThat(created)
                    .as("★ 追加したあと読む画面へ飛ばしている。"
                            + "作った次にやりたいのは中身を入れることなので、その場所へ着地させる")
                    .endsWith("/edit")
                    .startsWith("/admin/categories/");
        } finally {
            categoryRepository.findAll().stream()
                    .filter(c -> c.getName().startsWith("戻り先の確認")
                            || c.getName().equals("新しく作った"))
                    .forEach(categoryRepository::delete);
        }
    }

    @Test
    @DisplayName("★ 列幅は設計 ト11 どおり 340 / 140 / 300 / 180 / 160（合計 1120）")
    void columnWidths() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));

        // ★ 2026-09-20：nth-child をやめてクラス指定にしました。
        //   列は th:if で消えることがあり、nth-child だと 1 本減った瞬間に
        //   別の列へ幅が当たります（.table--recipes が先にこの形です）。
        int sum = 0;
        for (Object[] col : new Object[][]{
                {"col-name", 340}, {"col-count", 140}, {"col-state", 300},
                {"col-act", 180}, {"col-order", 160}}) {
            String rule = ".table--cats th." + col[0] + ", .table--cats td." + col[0]
                    + " { width: " + col[1] + "px; }";
            assertThat(css).as(col[0] + " の幅指定が無い").contains(rule);
            sum += (int) col[1];
        }
        assertThat(sum)
                .as("★ 合計が 1120 でない。fixed なので、狭いと右が余り、"
                        + "広いと設計幅でも横スクロールが出る")
                .isEqualTo(1120);

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

    /**
     * ★「先にカテゴリを作ってください」は、作る画面へ連れて行くこと。
     *
     * <p>2026-09-20 に行き先が {@code /new} へ変わりました。
     * 読む画面に着くと、そこからもう一手要ります。
     */
    @Test
    @DisplayName("カテゴリを作る案内は、作る画面へ連れて行く")
    void createLinksPointAtTheNewScreen() throws Exception {
        for (Path p : new Path[]{
                Path.of("src/main/resources/templates/admin/items.html"),
                Path.of("src/main/resources/templates/admin/item-form.html")}) {
            assertThat(Files.readString(p))
                    .as(p + " の案内が作る画面を指していない")
                    .contains("@{/admin/categories/new}\">カテゴリ</a> を作ってください");
        }
    }
}
