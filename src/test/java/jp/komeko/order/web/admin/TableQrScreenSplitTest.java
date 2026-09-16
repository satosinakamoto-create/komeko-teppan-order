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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 卓と QR コードを「読む画面」と「直す画面」に分ける（2026-09-14、店主の判断）。
 *
 * <p><b>なぜ分けるか。</b>Figma の 07 ページ（ト12 卓・ト13 QRコード）は
 * どちらも<b>見出し＋一覧だけ</b>で、追加フォームが本文にない。
 * 実装は一覧と追加フォームが同居していて、開いた瞬間に書き換えられる画面が出る。
 * カテゴリ（2026-09-07）とスタッフで同じ判断をしているので、形をそろえる。
 *
 * <p>開く理由のほとんどは「いま何卓あるか」「どの QR がどの卓か」を<b>見る</b>ことで、
 * 直すのはたまに。見るために開いた画面に押し間違いの的を常に置かない。
 *
 * <p><b>分け方はカテゴリと同じ</b>
 * <pre>
 *   GET /admin/tables       … 読む（表だけ）→ 見出しに「卓を 編集・追加」
 *   GET /admin/tables/edit  … 直す（追加フォーム＋行ごとの更新・削除）
 *   GET /admin/qr           … 読む（QR の並びだけ）→ 見出しに「QR を 編集・再発行」
 *   GET /admin/qr/edit      … 直す（再発行・base-url の確認）
 * </pre>
 * 更新・削除の POST は今までのまま（{@code /admin/tables/{id}} など）。
 * 送信先を変えると、ブックマークや戻るボタンからの再送信が壊れる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("卓と QR の一覧・編集の分離")
class TableQrScreenSplitTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 卓が 1 つも無いと、QR のカードが 1 枚も描かれない。
     * 「再発行が出ているか」を確かめるテストが、
     * <b>中身が空だから通る／落ちる</b>という当てにならない形になるので、
     * 必ず 1 卓作ってから見る。
     */
    @Autowired
    private jp.komeko.order.service.TableService tableService;

    private void ensureTable() {
        if (tableService.allTables().isEmpty()) {
            tableService.createTable("分割テスト卓", 4, 10, null);
        }
    }

    private static final Path TPL = Path.of("src/main/resources/templates");

    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n");
    }

    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /** 本文だけを見る（共通レイアウトのフォームを数えないため）。 */
    private String main(String html) {
        int at = html.indexOf("<main");
        assertThat(at).as("<main> が無い").isGreaterThanOrEqualTo(0);
        return html.substring(at, html.lastIndexOf("</main>"));
    }

    // ---------------------------------------------------------------- 卓

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 卓の一覧は読むだけ（入力欄も追加フォームも出さない）")
    void tableListIsReadOnly() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/tables"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/table-list"))
                .andReturn().getResponse().getContentAsString();

        // ★ 文言ではなく実体で見る。「入力欄が無い」は <input> の有無が答え
        assertThat(main(html)).as("読む画面に入力欄が残っている").doesNotContain("<input");
        // ★ 2026-09-15 に入口を 2 つへ割った（TableAddEditSplitTest）。
        //   もとは「卓を 編集・追加」の 1 つだった
        assertThat(main(html)).contains("＋新規追加");
        assertThat(main(html)).contains("編集");
    }

    /**
     * ★ 2026-09-15 に「足す」を {@code /admin/tables/new} へ分けました
     * （{@link TableAddEditSplitTest}）。ここは<b>直す</b>画面なので、
     * 行ごとの入力欄はあるが、追加フォームは無いのが正です。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 卓の編集画面には行ごとの入力欄がある（追加フォームは別画面）")
    void tableEditHasTheRowForms() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/tables/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/tables"))
                .andReturn().getResponse().getContentAsString();
        assertThat(main(html)).contains("<input");
        assertThat(main(html)).contains("登録済みの卓");
        assertThat(main(html)).as("追加フォームが残っている").doesNotContain("卓を追加");
    }

    // ---------------------------------------------------------------- QR

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ QR の一覧は読むだけ（再発行のボタンを置かない）")
    void qrListIsReadOnly() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/qr"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/qr-list"))
                .andReturn().getResponse().getContentAsString();

        // 再発行は「この卓の QR を無効にする」操作。読む画面には置かない
        assertThat(main(html)).as("読む画面に再発行が残っている").doesNotContain("/regenerate");
        assertThat(main(html)).contains("QR を 編集・再発行");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ QR の編集画面には再発行がある")
    void qrEditHasRegenerate() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/qr/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/qr"))
                .andReturn().getResponse().getContentAsString();
        assertThat(main(html)).contains("/regenerate");
    }

    // ---------------------------------------------------------------- 形

    @Test
    @DisplayName("★ 一覧は Figma どおり「見出し＋表」だけ（説明文を並べない）")
    void theListScreensMatchTheMock() throws Exception {
        for (String name : new String[]{"admin/table-list.html", "admin/qr-list.html"}) {
            String m = withoutComments(main(tpl(name)));
            assertThat(m).as(name + " の見出しが帯でない").contains("page-head__title");
            // 本文のいちばん上に長い説明（.lead）を置かない。
            // Figma の本文は「見出し → 表」の 2 ブロック
            assertThat(m).as(name + " に説明文（lead）が残っている").doesNotContain("class=\"lead\"");
        }
    }
}
