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

/**
 * QR を卓の画面に寄せ、サイドバーから「QRコード」を外す（2026-09-16、店主の判断）。
 *
 * <p><b>なぜ寄せるか。</b>
 * <ol>
 *   <li>{@code accessToken}（QR の中身）は {@code DiningTable} が持っていて、
 *       再発行の送信先も<b>もともと</b> {@code /admin/tables/&#123;id&#125;/regenerate} でした。
 *       データの持ち主は最初から卓で、画面だけが分かれていた</li>
 *   <li>いちばん多い作業「1 卓だけ貼り替える」は、2026-09-15 に足した行ごとの
 *       「印刷」でできるようになった。QR 画面に残った用事は
 *       「全部並べて眺める」だけになった</li>
 *   <li>刷る入口が卓と QR の 2 か所にあった。
 *       「同じ操作の入口を 2 つ持たない」は何度か採ってきた判断</li>
 * </ol>
 *
 * <p><b>持っていくのを忘れてはいけないもの。</b>
 * {@code base-url} の警告です。{@code localhost} のままだと
 * <b>印刷してもお客さまのスマホからは開けません</b>。
 * しかもスタッフの PC からは正常に動くので、この警告が唯一気づける手段です。
 * QR 画面にしか無かったので、卓の編集画面へ移します。
 *
 * <p><b>消さないもの。</b>印刷シート（{@code /admin/qr/print}）は実際に紙を出す画面なので残します。
 * 再発行（{@code /admin/qr/edit}）も、卓から辿れる場所に置いたまま残します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("QR は卓の画面へ寄せる")
class QrMovesIntoTablesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private jp.komeko.order.service.TableService tableService;

    private void ensureTable() {
        if (tableService.allTables().isEmpty()) {
            tableService.createTable("QR移設テスト卓", 4, 10, null);
        }
    }

    private static final Path TPL = Path.of("src/main/resources/templates");

    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- サイドバー

    @Test
    @DisplayName("★ サイドバーから「QRコード」を外す")
    void theSidebarDropsTheQrEntry() throws Exception {
        String layout = tpl("layout/staff.html");
        assertThat(layout)
                .as("サイドバーに QRコード が残っている")
                .doesNotContain("<span>QRコード</span>");
        // 卓は残る（QR はここから辿る）
        assertThat(layout).contains("<span>卓</span>");
    }

    /**
     * サイドバーから消しても、URL を直接開けば見られる状態は残します。
     * ブックマークしている人を 404 で突き放さないためです。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ URL を直接開けば QR 画面はまだ見られる")
    void theQrScreenStillAnswers() throws Exception {
        ensureTable();
        mockMvc.perform(get("/admin/qr")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/qr/print")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 警告の移設

    @Test
    @DisplayName("★ base-url の警告を卓の編集画面に置く（唯一の気づける場所）")
    void theBaseUrlWarningMovesToTheTableScreen() throws Exception {
        String edit = tpl("admin/tables.html");
        assertThat(edit)
                .as("印刷しても客のスマホから開けないことの警告が無い")
                .contains("お客さまのスマホからは開けません");
        assertThat(edit)
                .as("localhost かどうかで出し分けていない")
                .contains("#strings.contains(baseUrl, 'localhost')");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 卓の編集画面に baseUrl が渡っている")
    void theControllerHandsOverTheBaseUrl() throws Exception {
        ensureTable();
        mockMvc.perform(get("/admin/tables/edit"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .model().attributeExists("baseUrl"));
    }

    // ---------------------------------------------------------------- 再発行

    /**
     * 再発行は押すと貼ってある QR が読めなくなり、
     * 貼り替えるまでその席から注文できません。
     * 日常的に押す「印刷」の隣には置かないこと。
     */
    @Test
    @DisplayName("★ 再発行は印刷と並べない（卓の編集画面に置く場合も別の行へ）")
    void theRegenerateDoesNotSitNextToPrint() throws Exception {
        String edit = tpl("admin/tables.html");
        int print = edit.indexOf("/admin/qr/print(tableId");
        int regen = edit.indexOf("/regenerate");
        if (regen < 0) {
            return;   // 卓の画面に置かない判断ならそれでよい
        }
        assertThat(Math.abs(print - regen))
                .as("印刷と再発行が近すぎる（押し間違いが起きる）")
                .isGreaterThan(200);
    }
}
