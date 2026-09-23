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
 * スタッフを「読む」「足す」「直す」の 3 画面に分ける（2026-09-20、店主の判断）。
 *
 * <p>店主の言葉：「スタッフ追加とアカウント一覧を分けて表示して欲しい。
 * 情報量が多くてユーザーに負荷がかかるからさ」
 *
 * <p><b>これまで</b>は 2 画面でした。
 * <pre>
 *   /admin/staff        … 読む（表だけ）
 *   /admin/staff/edit   … 足す＋直す（追加フォームとアカウント一覧が同居）
 * </pre>
 * スタッフを 1 人足しに来ただけでも、下に全員ぶんの入力欄・パスワード欄・削除ボタンが
 * 並びます。<b>消すと戻せないものが並ぶ画面</b>なので、用事のない人にまで
 * 見せ続ける理由がありません。
 *
 * <p><b>これから</b>（設計 ト15c 1249:13925 「スタッフを追加」／ト15b 838:9390 「アカウント一覧」）
 * <pre>
 *   /admin/staff        … 読む → ボタンは「編集」と「＋新規追加」の 2 つ
 *   /admin/staff/new    … 足す → 追加フォームだけ
 *   /admin/staff/edit   … 直す → アカウント一覧だけ（追加フォームは置かない）
 * </pre>
 *
 * <p><b>POST 先は変えません。</b>追加は {@code POST /admin/staff}、更新は
 * {@code POST /admin/staff/{id}} のままです。送信先を変えると、ブックマークや
 * 戻るボタンからの再送信が壊れます（卓・カテゴリを分けたときと同じ判断）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("スタッフの 読む・足す・直す の分離")
class StaffAddEditSplitTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path TPL = Path.of("src/main/resources/templates/admin");

    /** コメントを落とした本文。「書いてあるのに出ていない」を見抜くため、これで判定する。 */
    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 追加フォームは「足す」画面にだけある。
     *
     * <p>これがこの分割のすべてです。片方に移すだけでなく、
     * <b>もとの画面から消えていること</b>まで見ないと、同じものが 2 画面に出ます。
     */
    @Test
    @DisplayName("★ 追加フォームは追加画面にだけあり、編集画面には無い")
    void theAddFormLivesOnlyOnTheAddScreen() throws Exception {
        String add = tpl("staff-new.html");
        String edit = tpl("staff.html");

        assertThat(add)
                .as("追加画面に追加フォームが無い")
                .contains("${staffForm}").contains("このスタッフを追加する");

        assertThat(edit)
                .as("★ 編集画面に追加フォームが残っている。分けた意味が無い")
                .doesNotContain("${staffForm}");
        assertThat(edit)
                .as("★ 編集画面に「スタッフを追加する」のカードが残っている")
                .doesNotContain("このスタッフを追加する");
    }

    /**
     * ★ アカウント一覧は「直す」画面に残っている。
     *
     * <p>分けるときに、消してはいけないほうを消していないことの確認です。
     */
    @Test
    @DisplayName("★ 編集画面にアカウント一覧が残っている")
    void theAccountListStaysOnTheEditScreen() throws Exception {
        String edit = tpl("staff.html");

        assertThat(edit).as("アカウントのカードが無い").contains("staffcard");
        assertThat(edit).as("更新ボタンが無い").contains("この内容で更新する");
        assertThat(edit).as("削除ボタンが無い").contains("/delete");
    }

    /**
     * ★ 一覧の帯は「編集」と「＋新規追加」の 2 つ。
     *
     * <p>設計 ト15（732:4888）。卓（{@code table-list.html}）と同じ形にそろえます。
     * 1 つにまとまっていると、追加したいだけの人も編集画面を経由することになります。
     */
    @Test
    @DisplayName("★ 一覧の帯に［編集］と［＋新規追加］が並ぶ")
    void theListHeadHasTwoButtons() throws Exception {
        String list = tpl("staff-list.html");

        assertThat(list)
                .as("★ 1 つにまとまったままのボタンが残っている")
                .doesNotContain("スタッフ 編集・追加");

        int edit = list.indexOf("/admin/staff/edit");
        int add = list.indexOf("/admin/staff/new");
        assertThat(edit).as("編集への行き先が無い").isGreaterThan(0);
        assertThat(add).as("★ 追加への行き先が無い").isGreaterThan(0);
        assertThat(add).as("＋新規追加が編集より前にある（設計は 編集 → ＋新規追加 の順）")
                .isGreaterThan(edit);

        assertThat(list).as("＋新規追加の文言が違う").contains("＋新規追加");
    }

    /** ★ 追加画面が開けること。開けなければ「足す」手段が消える。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ /admin/staff/new が開ける")
    void theAddScreenOpens() throws Exception {
        mockMvc.perform(get("/admin/staff/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/staff-new"));
    }

    /**
     * ★ 追加画面に戻り口がある。
     *
     * <p>下り画面に戻り口が無いと、サイドバーを使えない人が行き止まりになります
     * （ト15b で同じ指摘を受けています）。
     */
    @Test
    @DisplayName("★ 追加画面に一覧への戻り口がある")
    void theAddScreenHasAWayBack() throws Exception {
        String add = tpl("staff-new.html");

        assertThat(add).as("★ 戻り口が無い。行き止まりになる")
                .contains("スタッフ一覧へ戻る");
        assertThat(add).as("戻り先が一覧になっていない")
                .contains("@{/admin/staff}");
    }

    /**
     * ★ 追加の送り先は変えない。
     *
     * <p>{@code POST /admin/staff} のままにします。変えると、
     * 開きっぱなしのタブからの送信や戻るボタンでの再送信が 404 になります。
     */
    @Test
    @DisplayName("★ 追加の POST 先は /admin/staff のまま")
    void theCreateEndpointIsUnchanged() throws Exception {
        String add = tpl("staff-new.html");

        assertThat(add)
                .as("★ 追加の送り先が変わっている。開きっぱなしのタブからの送信が壊れる")
                .contains("th:action=\"@{/admin/staff}\"");
    }
}
