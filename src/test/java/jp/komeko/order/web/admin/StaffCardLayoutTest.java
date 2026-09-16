package jp.komeko.order.web.admin;

import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import jp.komeko.order.repository.StaffUserRepository;
import jp.komeko.order.service.StaffUserService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * スタッフ 編集・追加を 1 人 1 枚のカードにする（設計 ト15b 838:9390・2026-09-17）。
 *
 * <p><b>店主の指摘。</b>「この内容で更新する」の下に削除ボタンがあって UI が悪い、
 * 上下ではなく左右に組み直してほしい。
 *
 * <p>設計にもそのまま出ていました。ホール田中のカードだけ削除を下に積んだせいで
 * 高さが 136px、ほかの 2 枚は 110px。横に並べれば 3 枚とも揃います。
 *
 * <p><b>あわせて作りを変えました。</b>それまでは
 * 「表の行に削除だけ →『編集』を開くと表示名と権限 →『パスワードを変更』を開くと
 * パスワード」という 3 段のアコーディオンでした。設計は全部その場に出しています。
 *
 * <p><b>いちばん壊れると痛いのはパスワードです。</b>同じフォームに入れたので、
 * 空欄のまま更新したときに<b>パスワードが空文字で上書きされない</b>ことを
 * 実際に保存して確かめます。ここが壊れると、表示名を直しただけで
 * 本人がログインできなくなります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("スタッフは 1 人 1 枚のカード")
class StaffCardLayoutTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/staff.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StaffUserService staffUserService;
    @Autowired
    private StaffUserRepository staffUserRepository;

    private StaffUser target;

    @BeforeEach
    void setUp() {
        String name = "cardtest" + System.nanoTime();
        target = staffUserService.create(name, "initial-password", "カード試験", StaffRole.STAFF);
    }

    private String template() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    // ------------------------------------------------------------------
    // 店主の指示そのもの
    // ------------------------------------------------------------------

    /**
     * ★ 更新と削除が<b>同じ横並びの箱</b>に入っていること。
     *
     * <p>順番も見ます。削除が先に来ると、押すつもりのないほうに指が行きます。
     */
    @Test
    @DisplayName("★ 更新と削除は同じ箱に、更新が先、削除が後")
    void theTwoButtonsSitSideBySide() throws Exception {
        String html = template();

        int box = html.indexOf("staffcard__act");
        assertThat(box).as("操作の箱（.staffcard__act）が無い").isGreaterThan(0);

        String act = html.substring(box, html.indexOf("</div>", box));
        int update = act.indexOf("この内容で更新する");
        int delete = act.indexOf("削除</button>");

        assertThat(update).as("更新が操作の箱の中に無い").isGreaterThan(0);
        assertThat(delete).as("削除が操作の箱の中に無い").isGreaterThan(0);
        assertThat(update).as("削除が更新より先に来ている").isLessThan(delete);
    }

    /**
     * ★ 横並びであること（CSS 側）。
     *
     * <p>テンプレートで同じ箱に入れても、CSS が縦積みなら見た目は変わりません。
     */
    @Test
    @DisplayName("★ 操作の箱は横並び（縦積みにしない）")
    void theActionBoxIsHorizontal() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        int at = css.indexOf(".staffcard__act {");
        assertThat(at).as(".staffcard__act が app.css に無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("flex になっていない").contains("display: flex");
        assertThat(rule).as("縦積みになっている（column が指定されている）")
                .doesNotContain("column");
    }

    // ------------------------------------------------------------------
    // カードの作り
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 表とアコーディオンをやめて、その場で直せる")
    void theTableAndAccordionAreGone() throws Exception {
        String html = template();

        assertThat(html).as("まだ表で組んでいる").doesNotContain("<table");
        assertThat(html).as("まだアコーディオンで隠している").doesNotContain("<details");
        assertThat(html).as("カードになっていない").contains("staffcard__form");
    }

    /**
     * ★ 削除は別の URL への POST なので、formaction で送り先だけ変える。
     *
     * <p>HTML はフォームを入れ子にできないため、更新フォームの中に削除フォームは置けません。
     */
    @Test
    @DisplayName("★ 削除は formaction で別の URL へ送る")
    void deleteUsesFormaction() throws Exception {
        String html = template();

        assertThat(html).as("削除が formaction を使っていない")
                .contains("th:formaction=\"@{/admin/staff/{id}/delete(id=${u.id})}\"");
    }

    /**
     * ★「ログインを許可する」は設計に無いが残す。
     *
     * <p>外すとフォームが {@code enabled} を送らなくなり、コントローラ側の既定
     * （{@code defaultValue = "false"}）が入ります。つまり表示名を直して更新しただけで
     * その人がログインできなくなります。
     */
    @Test
    @DisplayName("★ ログインを許可する は残っている（外すと更新で無効化されてしまう）")
    void theEnabledCheckboxSurvives() throws Exception {
        assertThat(template())
                .as("enabled のチェックボックスが消えている")
                .contains("name=\"enabled\"");
    }

    // ------------------------------------------------------------------
    // パスワード（ここが壊れると本人が締め出される）
    // ------------------------------------------------------------------

    /**
     * ★ 空欄のまま更新しても、パスワードは変わらない。
     *
     * <p>画面に「変えないなら空のまま」と書いてあるとおりの動き。
     * 空文字を「空のパスワードにしたい」と解釈しないことを、実際に保存して確かめます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ パスワード欄が空なら、パスワードは変わらない")
    void blankPasswordLeavesItAlone() throws Exception {
        String before = staffUserRepository.findById(target.getId()).orElseThrow().getPasswordHash();

        mockMvc.perform(post("/admin/staff/{id}", target.getId()).with(csrf())
                        .param("displayName", "カード試験 あらため")
                        .param("role", StaffRole.STAFF.name())
                        .param("enabled", "true")
                        .param("password", ""))
                .andExpect(status().is3xxRedirection());

        StaffUser after = staffUserRepository.findById(target.getId()).orElseThrow();
        assertThat(after.getDisplayName()).as("表示名が保存されていない").isEqualTo("カード試験 あらため");
        assertThat(after.getPasswordHash())
                .as("空欄なのにパスワードが書き換わった（本人がログインできなくなる）")
                .isEqualTo(before);
    }

    /** ★ 入れたときは、同じ「この内容で更新する」で変わる。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ パスワードを入れれば、同じボタンで変わる")
    void aNewPasswordIsApplied() throws Exception {
        String before = staffUserRepository.findById(target.getId()).orElseThrow().getPasswordHash();

        mockMvc.perform(post("/admin/staff/{id}", target.getId()).with(csrf())
                        .param("displayName", "カード試験")
                        .param("role", StaffRole.STAFF.name())
                        .param("enabled", "true")
                        .param("password", "brand-new-password"))
                .andExpect(status().is3xxRedirection());

        assertThat(staffUserRepository.findById(target.getId()).orElseThrow().getPasswordHash())
                .as("パスワードが変わっていない").isNotEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 実際に描けること
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 画面が描けて、カードに 4 つの欄と 2 つのボタンが出る")
    void theScreenRenders() throws Exception {
        String html = mockMvc.perform(get("/admin/staff/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("カードが出ていない").contains("staffcard__form");
        assertThat(html).as("ユーザー名が出ていない").contains(target.getUsername());
        assertThat(html).as("新しいパスワードの欄が無い").contains("新しいパスワード");
        assertThat(html).as("更新ボタンが無い").contains("この内容で更新する");
        assertThat(html).as("削除ボタンが無い").contains("削除</button>");
    }
}
