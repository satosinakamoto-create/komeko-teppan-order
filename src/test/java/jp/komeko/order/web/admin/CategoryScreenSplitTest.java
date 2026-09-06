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
        assertThat(main).as("読む画面に入力欄が残っている").doesNotContain("<input");

        // ★ 文言ではなく実体で見ること。
        //   「カテゴリを追加」という語は、1 件も無いときの案内リンクにも出る。
        //   語で判定すると、その案内があるだけで落ちる（実際に落とした）
        assertThat(main).as("読む画面から書き換えられる").doesNotContain("method=\"post\"");

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
        String java = Files.readString(
                Path.of("src/main/java/jp/komeko/order/web/admin/AdminCategoryController.java"));
        assertThat(java.split("redirect:/admin/categories/edit", -1).length - 1)
                .as("保存後に読む画面へ戻る口が残っている").isEqualTo(7);
        assertThat(java).as("素の一覧へ戻す redirect が残っている")
                .doesNotContain("\"redirect:/admin/categories\"");
    }

    @Test
    @DisplayName("★ 列幅は設計どおり 400 / 200 / 160 / 360（合計 1120）")
    void columnWidths() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".table--cats th:nth-child(1), .table--cats td:nth-child(1) { width: 400px; }");
        assertThat(css).contains(".table--cats th:nth-child(2), .table--cats td:nth-child(2) { width: 200px; }");
        assertThat(css).contains(".table--cats th:nth-child(3), .table--cats td:nth-child(3) { width: 160px; }");
        assertThat(css).contains(".table--cats th:nth-child(4), .table--cats td:nth-child(4) { width: 360px; }");
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
