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
 * 卓は「読む」「足す」「直す」の 3 画面に分ける（2026-09-15、店主の判断）。
 *
 * <p><b>これまで</b>は 2 画面でした。
 * <pre>
 *   /admin/tables       … 読む（表だけ）
 *   /admin/tables/edit  … 足す＋直す（追加フォームと一覧が同居）
 * </pre>
 * 「足す」と「直す」が 1 画面に同居していると、卓を 1 つ足しに来ただけでも
 * 12 卓ぶんの入力欄が下に並びます。逆に 1 卓の席数を直しに来た人は、
 * 上の追加フォームを読み飛ばしてから目的の行を探すことになります。
 *
 * <p><b>これから</b>（設計 912:13548「卓の新規追加」／907:1820「卓の編集」）
 * <pre>
 *   /admin/tables       … 読む   → ボタンは「＋新規追加」と「編集」の 2 つ
 *   /admin/tables/new   … 足す   → 追加フォームだけ
 *   /admin/tables/edit  … 直す   → 登録済みの卓だけ（追加フォームは置かない）
 * </pre>
 *
 * <p>更新・削除の POST 先は変えません。送信先を変えると、
 * ブックマークや戻るボタンからの再送信が壊れます（卓・QR を分けたときと同じ判断）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("卓の 読む・足す・直す の分離")
class TableAddEditSplitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private jp.komeko.order.service.TableService tableService;

    /**
     * 卓が 0 件だと一覧の中身が空になり、
     * 「入力欄が無い」が中身ゼロのせいで通ってしまいます。必ず 1 卓作ってから見ること
     * （{@code TableQrScreenSplitTest} が同じ罠を踏みました）。
     */
    private void ensureTable() {
        if (tableService.allTables().isEmpty()) {
            tableService.createTable("分割テスト卓", 4, 10, null);
        }
    }

    private static final Path TPL = Path.of("src/main/resources/templates");

    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    private String main(String html) {
        int at = html.indexOf("<main");
        assertThat(at).as("<main> が無い").isGreaterThanOrEqualTo(0);
        return html.substring(at, html.lastIndexOf("</main>"));
    }

    // ---------------------------------------------------------------- 読む

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧のボタンは「＋新規追加」と「編集」の 2 つ")
    void theListOffersBothDoors() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/tables"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/table-list"))
                .andReturn().getResponse().getContentAsString();

        assertThat(main(html)).contains("＋新規追加");
        assertThat(main(html)).contains("編集");
        assertThat(main(html)).as("新規追加への入口が無い").contains("/admin/tables/new");
        assertThat(main(html)).as("編集への入口が無い").contains("/admin/tables/edit");
    }

    // ---------------------------------------------------------------- 足す

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 新規追加は追加フォームだけ（登録済みの一覧を並べない）")
    void theNewScreenOnlyAdds() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/tables/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/table-new"))
                .andReturn().getResponse().getContentAsString();

        assertThat(main(html)).contains("卓の新規追加");
        assertThat(main(html)).contains("追加する");
        // 「登録済みの卓」の節ごと出さない。足しに来た人に 12 卓ぶんの入力欄を見せない
        assertThat(main(html)).as("登録済みの一覧が残っている").doesNotContain("登録済みの卓");
    }

    // ---------------------------------------------------------------- 直す

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 編集は登録済みの卓だけ（追加フォームを置かない）")
    void theEditScreenOnlyEdits() throws Exception {
        ensureTable();
        String html = mockMvc.perform(get("/admin/tables/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/tables"))
                .andReturn().getResponse().getContentAsString();

        assertThat(main(html)).contains("卓の編集");
        assertThat(main(html)).contains("登録済みの卓");
        assertThat(main(html)).as("追加フォームが残っている").doesNotContain("卓を追加");
    }

    @Test
    @DisplayName("★ 編集の見出しの右に「まとめて印刷」、行ごとに「印刷」")
    void theEditScreenCarriesThePrintButtons() throws Exception {
        String m = main(tpl("admin/tables.html"));
        assertThat(m).as("まとめて印刷が無い").contains("まとめて印刷");
        assertThat(m).as("行ごとの印刷が無い").contains("/admin/qr/print");
    }

    // ---------------------------------------------------------------- 送信先

    /**
     * 画面を割っても、更新・削除の送信先は変えません。
     * ブックマークや戻るボタンからの再送信が壊れるためです。
     */
    @Test
    @DisplayName("★ 追加・更新・削除の送信先は今までのまま")
    void thePostTargetsDoNotMove() throws Exception {
        String addForm = main(tpl("admin/table-new.html"));
        assertThat(addForm).as("追加の送信先が変わっている")
                .contains("th:action=\"@{/admin/tables}\"");

        String editForm = main(tpl("admin/tables.html"));
        assertThat(editForm).contains("@{/admin/tables/{id}(id=${t.id})}");
        assertThat(editForm).contains("@{/admin/tables/{id}/delete(id=${t.id})}");
    }
}
