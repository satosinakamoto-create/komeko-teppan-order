package jp.komeko.order.accountant.web;

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
 * 税理士の画面で月を直接選べるようにする（2026-09-17、店主の指摘）。
 *
 * <p><b>何が起きていたか。</b>月の移動が「← 前月」「翌月 →」の 2 つだけでした。
 * いまが 2026-09 で 2025-12 を見たいとき、<b>9 回押す</b>ことになります。
 * 税理士の画面は過去の月を見に来る画面なので、ここが一番効きます。
 *
 * <p><b>置き場所は帯の中。</b>月のナビはレイアウト（{@code layout/accountant.html}）に
 * あって、どの画面でも同じ位置に出ます。選ぶ口も同じ場所に置かないと、
 * 画面によって探し方が変わります。
 *
 * <p><b>JavaScript は使いません。</b>スタッフ側の画面は素の HTML で動かす決まりです
 * （CLAUDE.md）。{@code <input type="month">} と送信ボタンの GET フォームにします。
 * ブラウザが月のカレンダーを出してくれるので、自前で組む必要がありません。
 *
 * <p>{@code month} モデル属性は {@code 2026-09} の形で、
 * これは {@code <input type="month">} の値の形そのものです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("税理士の画面で月を直接選べる")
class AccountantMonthPickTest {

    private static final Path LAYOUT =
            Path.of("src/main/resources/templates/fragments/common.html");

    @Autowired
    private MockMvc mockMvc;

    private String layout() throws Exception {
        return Files.readString(LAYOUT).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 月の入力欄がレイアウトにある（前月・翌月だけではない）")
    void thereIsAMonthInput() throws Exception {
        String html = layout();

        assertThat(html).as("月の入力欄が無い").contains("type=\"month\"");
        assertThat(html).as("月の入力欄が month という名前で送られていない")
                .contains("name=\"month\"");
        assertThat(html).as("前月・翌月が消えている（併存させる）").contains("← 前月");
    }

    /**
     * ★ 並びと文言を注文履歴にそろえる（店主の指示・2026-09-17）。
     *
     * <p>注文履歴（{@code admin/orders.html}）は、前からこの形でした。
     *
     * <pre>
     *   ← 前日 ／ 日付の入力 ／ この日を表示 ／ 翌日 →
     * </pre>
     *
     * <p>税理士の帯は「← 前月 ／ 翌月 → ／ 月の入力 ／ 表示」と、
     * 移動ボタンが 2 つ先に来たうえに文言も違いました。同じ役の並びが画面ごとに
     * 違うと、押す前に毎回読むことになります。
     *
     * <p>「対象の月 2026-08」の文字も外しました。入力欄に同じ月が出ているので、
     * 同じことを 2 か所で言っていたためです（注文履歴も入力欄だけです）。
     */
    @Test
    @DisplayName("★ 並びと文言が注文履歴とそろっている（前 → 入力 → 表示 → 次）")
    void theOrderMatchesTheOrderHistoryScreen() throws Exception {
        String html = layout();

        int prev = html.indexOf("← 前月");
        int input = html.indexOf("type=\"month\"");
        int show = html.indexOf("この月を表示");
        int next = html.indexOf("翌月 →");

        assertThat(prev).as("← 前月 が無い").isGreaterThan(0);
        assertThat(input).as("月の入力欄が無い").isGreaterThan(0);
        assertThat(show).as("「この月を表示」が無い（注文履歴は「この日を表示」）").isGreaterThan(0);
        assertThat(next).as("翌月 → が無い").isGreaterThan(0);

        assertThat(prev).as("入力欄が 前月 より前にある").isLessThan(input);
        assertThat(input).as("表示ボタンが入力欄より前にある").isLessThan(show);
        assertThat(show).as("翌月が表示ボタンより前にある").isLessThan(next);

        // ★ 2026-09-18: .row row--wrap はやめました。題の行の中に入ったので、
        //    並びは .datenav 自身が持ちます（app.css の .datenav）。
        assertThat(html).as("帯のクラス（.datenav）が付いていない")
                .contains("class=\"datenav\"");
        assertThat(html).as("「対象の月」の文字が残っている（入力欄と二重）")
                .doesNotContain("対象の月");
    }

    /**
     * ★ 送信は GET。
     *
     * <p>月を見るのは読むだけの操作なので、URL に残って戻る・進むが効き、
     * ブックマークもできる形にします。POST にすると再読み込みで警告が出ます。
     */
    @Test
    @DisplayName("★ 月の切り替えは GET（URL に残る）")
    void theMonthIsSwitchedWithGet() throws Exception {
        String html = layout();

        int at = html.indexOf("type=\"month\"");
        assertThat(at).as("月の入力欄が無い").isGreaterThan(0);

        String form = html.substring(Math.max(0, at - 400), at);
        assertThat(form).as("GET のフォームに入っていない").contains("method=\"get\"");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 今月のまとめが描けて、月の入力欄に今の月が入っている")
    void theSummaryRendersWithTheCurrentMonth() throws Exception {
        String html = mockMvc.perform(get("/accountant").param("month", "2026-08"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("月の入力欄が描かれていない").contains("type=\"month\"");
        assertThat(html).as("いま見ている月が入力欄に入っていない").contains("value=\"2026-08\"");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 選んだ月がそのまま反映される")
    void pickingAMonthMovesTheScreen() throws Exception {
        String html = mockMvc.perform(get("/accountant").param("month", "2025-12"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("選んだ月になっていない").contains("value=\"2025-12\"");
    }
}
