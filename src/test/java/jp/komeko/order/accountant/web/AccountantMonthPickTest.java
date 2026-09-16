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
            Path.of("src/main/resources/templates/layout/accountant.html");

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
