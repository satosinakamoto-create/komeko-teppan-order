package jp.komeko.order.web.admin;

import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import jp.komeko.order.repository.StaffUserRepository;
import jp.komeko.order.service.StaffUserService;
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
 * スタッフを「読む画面」と「直す画面」に分け、最終ログインを記録する
 * （2026-09-07 / 設計 13 スタッフ 41:2170）。
 *
 * <p><b>なぜ分けたか</b><br>
 * カテゴリと同じ理由に加えて、この画面はとくに危険です。
 * 並んでいるのは<b>消すと二度と戻せない</b>アカウントで、
 * 最後の管理者を消せばシステムに入れなくなります。
 * 「誰がログインできる状態か」を見に来ただけの画面に、
 * 削除ボタンを常時置いておく理由がありません。
 *
 * <p><b>最終ログインは null を許す</b><br>
 * null は「一度もログインしていない」という意味で使います。
 * 作ったまま使われていないアカウントが分かるので、
 * 空欄であること自体が情報です。作成日で埋めてはいけません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("スタッフの一覧と編集の分離")
class StaffScreenSplitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StaffUserService staffUserService;
    @Autowired
    private StaffUserRepository staffUserRepository;

    private static final Path LIST =
            Path.of("src/main/resources/templates/admin/staff-list.html");
    private static final Path EDIT =
            Path.of("src/main/resources/templates/admin/staff.html");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 開いた直後は読むだけ（削除ボタンも入力欄も出さない）")
    void listHasNoDestructiveControls() throws Exception {
        String html = mockMvc.perform(get("/admin/staff"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/staff-list"))
                .andReturn().getResponse().getContentAsString();

        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));
        // ★ 文言ではなく実体で見る。「削除」という語は説明文にも出うる
        assertThat(main).as("読む画面から書き換えられる").doesNotContain("method=\"post\"");
        assertThat(main).as("読む画面に入力欄が残っている").doesNotContain("<input");
        assertThat(main).as("直す口が無い").contains("/admin/staff/edit");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 直す画面には一覧へ戻る口がある")
    void editHasAWayBack() throws Exception {
        String html = mockMvc.perform(get("/admin/staff/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/staff"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("一覧へ戻る口が無い").contains("← スタッフ一覧へ");
    }

    /**
     * ★ 直したあとは直す画面に留まる。
     *
     * <p>守っているのは「1 人直すたびに読む画面へ飛ばされない」ことです。
     * 続けて別の人を直したいのに毎回一覧へ戻されると、そのつど
     * ［編集］を押し直すことになります。
     *
     * <p><b>2026-09-20：数を 4 → 3 に減らしました。</b>
     * 追加を {@code /admin/staff/new} へ切り出したので、
     * <b>追加だけは直す画面に戻りません</b>（足し終わった人の用は済んでいて、
     * 増えたことが見えるのは一覧のほうなので、一覧へ送ります）。
     * 残る 3 つ＝更新・パスワード変更・削除は、これまでどおり直す画面に留まります。
     */
    @Test
    @DisplayName("★ 更新・削除のあとは直す画面に留まる（追加だけは一覧へ）")
    void savingStaysOnTheEditScreen() throws Exception {
        String java = Files.readString(
                Path.of("src/main/java/jp/komeko/order/web/admin/AdminStaffController.java"));

        assertThat(java.split("redirect:/admin/staff/edit", -1).length - 1)
                .as("更新・パスワード変更・削除の戻り先が直す画面でなくなっている").isEqualTo(3);

        // ★ 一覧へ戻すのは「追加が成功したとき」の 1 回だけ。
        //   更新や削除まで一覧へ送ると、続けて直せなくなる。
        assertThat(java.split("\"redirect:/admin/staff\"", -1).length - 1)
                .as("読む画面へ戻す口が増えている。留まるべきは更新・削除").isEqualTo(1);

        // 追加の入力エラーは、足す画面へ戻して直し直せるようにする
        assertThat(java).as("追加の失敗時に足す画面へ戻していない")
                .contains("redirect:/admin/staff/new");
    }

    @Test
    @DisplayName("★ 最終ログインが記録される")
    void loginIsRecorded() {
        StaffUser user = staffUserService.create(
                "logintest" + (System.nanoTime() % 100000), "pass1234", "テスト", StaffRole.STAFF);

        // 作った直後は「まだログインなし」であること。
        // 作成日で埋めてしまうと、使われていないアカウントを見つけられなくなる
        assertThat(user.getLastLoginAt()).as("作っただけで記録が入っている").isNull();

        staffUserService.recordLogin(user.getUsername());

        assertThat(staffUserRepository.findById(user.getId()).orElseThrow().getLastLoginAt())
                .as("ログインしても記録されない").isNotNull();

        staffUserRepository.deleteById(user.getId());
    }

    @Test
    @DisplayName("★ 知らないユーザー名でも転ばない（ログインを止めない）")
    void unknownUserDoesNotThrow() {
        // 見学用のゲストなど staff_user に居ない相手でも呼ばれる。
        // ここで例外を投げると、記録できなかっただけでログインが失敗する
        staffUserService.recordLogin("この名前のスタッフは居ない" + System.nanoTime());
    }

    @Test
    @DisplayName("一度もログインしていない人は、空欄ではなくそう書く")
    void neverLoggedInIsSpelledOut() throws Exception {
        // 「—」だと「取れなかった」のか「まだ無い」のか区別できない
        assertThat(Files.readString(LIST)).contains("まだログインなし");
    }

    @Test
    @DisplayName("★ 列幅は設計どおり 320 / 280 / 220 / 300（合計 1120）")
    void columnWidths() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        // ★ 2026-09-20：330 → 320（設計 ト15 の実測値。2 列目が 270 → 280）。
        //   合計はどちらも 1120 なので、見た目では気づけませんでした。
        // 設計 320/280/220/300（合計 1120）を 1120 基準の割合で
        assertThat(css).contains(".table--staff th:nth-child(1), .table--staff td:nth-child(1) { width: 28.571%; }");  // 320
        assertThat(css).contains(".table--staff th:nth-child(2), .table--staff td:nth-child(2) { width: 25%; }");  // 280
        assertThat(css).contains(".table--staff th:nth-child(4), .table--staff td:nth-child(4) { width: 26.786%; }");  // 300
        assertThat(css).as("★ 列幅が px に戻っている（iPad で最終ログイン列が見切れる）")
                .doesNotContain(".table--staff th:nth-child(1), .table--staff td:nth-child(1) { width: 320px; }");
        assertThat(css).contains(".table--staff { table-layout: fixed; }");
    }
}
