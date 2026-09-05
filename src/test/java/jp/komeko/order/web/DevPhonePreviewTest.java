package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * お客さまのスマホ画面を実寸で見る確認用ページ（{@code /dev/phone}）のテスト。
 *
 * <p><b>いちばん守りたいのは「本番に出ないこと」です。</b><br>
 * 確認用の画面が実店舗で開けてしまうと、お客さまが QR から入った先で
 * 見えるはずのないものが見えます。しかも気づくのは誰かが URL を踏んだときで、
 * それは開店後かもしれません。
 *
 * <p><b>プロファイルではなく設定値で切っている理由</b><br>
 * {@code @Profile("dev")} にすると、test プロファイルで走るテストからは
 * 一度も触れられません。壊れても、誰も気づけない画面になります。
 * {@code app.dev-tools} という 1 つの値で
 * コントローラと認可の両方を決めているので、ここで両方を確かめられます。
 */
class DevPhonePreviewTest {

    @Nested
    @SpringBootTest(properties = "app.dev-tools=true")
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("開発時（app.dev-tools=true）")
    class Enabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("ログインなしで開ける（確認のたびにログインさせない）")
        void isOpenWithoutLogin() throws Exception {
            mockMvc.perform(get("/dev/phone")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("★ 既定で 390×844（iPhone と同じ実寸）の枠に、本物の画面を入れる")
        void showsRealScreenAtPhoneSize() throws Exception {
            String html = mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // 枠の実寸。ここがずれると「実機と同じ」と言いながら違うものを見ることになる
            assertThat(html).as("幅").contains("width:390px");
            assertThat(html).as("高さ").contains("height:844px");
            // 中身は本物の URL。写しや作り置きを埋め込んでいない
            assertThat(html).as("本物の画面を読んでいる").contains("<iframe");
            assertThat(html).as("既定の行き先").contains("/menu");
        }

        @Test
        @DisplayName("端末を選び直せる")
        void canPickAnotherDevice() throws Exception {
            String html = mockMvc.perform(get("/dev/phone").param("w", "440"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // iPhone 16 Pro Max。--page-max を 440 にした判断を目で確かめるための端末
            assertThat(html).contains("width:440px");
            assertThat(html).contains("height:956px");
        }

        @Test
        @DisplayName("知らない行き先は枠に入れない（外のサイトを埋め込む踏み台にしない）")
        void refusesUnknownTargets() throws Exception {
            String html = mockMvc.perform(get("/dev/phone").param("path", "https://example.com"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).as("外の URL をそのまま埋め込んでいる")
                    .doesNotContain("example.com");
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("本番と同じ設定（app.dev-tools を書かない）")
    class Disabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("★ 誰も開けない（ログインへ飛ばされる）")
        void isNotReachable() throws Exception {
            // 認可はディスパッチャより先に走るので、ここは 404 ではなく
            // 「ログインしてください」の 302 になる。届いていないことが大事で、
            // 何番で断られるかは重要ではない
            mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().is3xxRedirection());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("★ ログインしていても存在しない（コントローラごと作られていない）")
        void doesNotExistEvenForAdmin() throws Exception {
            // ここが本丸。認可を足し忘れても届かない、という二重の守りを確かめる。
            // 404 は「URL が無い」の意味で、app.dev-tools が無いと
            // DevPhonePreviewController の Bean 自体が作られないことの裏付け
            mockMvc.perform(get("/dev/phone")).andExpect(status().isNotFound());
        }
    }
}
