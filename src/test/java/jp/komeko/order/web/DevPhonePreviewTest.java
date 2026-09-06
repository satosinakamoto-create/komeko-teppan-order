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

        @Autowired
        private jp.komeko.order.repository.DiningTableRepository tables;

        private jp.komeko.order.domain.DiningTable seat;

        @org.junit.jupiter.api.BeforeEach
        void createSeat() {
            tables.deleteAll();
            seat = tables.save(new jp.komeko.order.domain.DiningTable("カウンター1", 1, 10));
        }

        @org.junit.jupiter.api.AfterEach
        void removeSeat() {
            tables.deleteAll();
        }

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
        @DisplayName("★ 窓に収まる大きさまで縮められる（ノート PC で枠がはみ出さない）")
        void fitsIntoASmallWindow() throws Exception {
            // 端末の枠は最大 440×956。ノート PC の窓は高さ 500〜700px しかないので、
            // 実寸のまま置くと必ずはみ出す。
            // 2026-09-06 に「スマホ確認しようとしても出来ない」と報告された状態がこれで、
            // 枠の下半分が見えないうえ、下ろすと画面の切り替えまで上へ消えていた。
            String html = mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // 縮小率を決めるのに要る 2 つの材料。どちらかが消えると
            // 縮小は既定の 1 倍に落ち、はみ出す状態に戻る
            assertThat(html).as("端末の幅を JavaScript に渡していない").contains("--phone-w:390");
            assertThat(html).as("端末の高さを JavaScript に渡していない").contains("--phone-h:844");
            // 既定は「窓に合わせる」。実寸を既定にすると、狭い窓では最初から見えない
            assertThat(html).as("窓に合わせる選択肢が無い").contains("窓に合わせる");
            // 切り替えを手元に残す（枠が長いので、無いと毎回ページ先頭へ戻ることになる）
            assertThat(html).as("操作部分を貼り付けていない").contains("phone-bar");
        }

        @Test
        @DisplayName("★ 縮めても中の版面は端末の幅のまま（見たいのは 390 のときの割り付け）")
        void keepsTheLayoutWidthWhenScaled() throws Exception {
            // 幅を小さくして縮めると、中の画面はその幅で組み直される。
            // 300px にした枠は「300px の端末」であって、確かめたい 390 の見え方ではない。
            // だから縮小は transform で行う。iframe に指定する寸法は実寸のまま残ること。
            String html = mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).as("iframe の幅が実寸でない").contains("width:390px");
            assertThat(html).as("iframe の高さが実寸でない").contains("height:844px");
            assertThat(html).as("縮小に transform を使っていない").contains("scale(var(--phone-scale");
        }

        @Test
        @DisplayName("★ 店舗端末（/staff/order）も枠に入れられる")
        void canPreviewTheStaffTerminal() throws Exception {
            // 店舗版は「店員のスマホで使う画面」なので、実寸で確かめられないと
            // 作ったものが現場で使えるか分からない。
            // 2026-09-06 まで行き先の一覧に無く、確認できなかった
            String html = mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).as("店舗端末を選べない").contains("/staff/order");

            // 選んだら実際に枠へ入ること（知らない行き先として弾かれない）
            String picked = mockMvc.perform(get("/dev/phone").param("path", "/staff/order"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(picked).as("枠に入らずメニューへ倒れている")
                    .contains("src=\"/staff/order\"");
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

        @Test
        @DisplayName("知らない卓のトークンも通さない")
        void refusesUnknownTableTokens() throws Exception {
            // 形（/t/…）が合っていても、実在する卓のものだけを通す。
            // 文字列の形だけで判定すると /t/../../ のような細工が抜ける
            String html = mockMvc.perform(get("/dev/phone")
                            .param("path", "/t/00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).as("知らないトークンを枠に入れている")
                    .doesNotContain("00000000-0000-0000-0000-000000000000");
        }

        @Test
        @DisplayName("★ 卓に入り直せる（再起動でセッションが消えても戻れる）")
        void offersAWayBackToATable() throws Exception {
            // アプリを再起動すると卓の紐づけが消え、確認中に
            // 「お席の QR をお読みください」に戻る。
            // そのたびにトークンを探させないための入口。
            String html = mockMvc.perform(get("/dev/phone"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).as("入り直す口が無い").contains("卓に入り直す");
            assertThat(html).as("卓の名前").contains("カウンター1");
            // 押した先が本物の QR の飛び先になっていること。
            // ここが違うと、押しても卓につかない見せかけのボタンになる
            assertThat(html).as("QR と同じ飛び先").contains("/t/" + seat.getAccessToken());
        }

        @Test
        @DisplayName("実在する卓のトークンは枠に入れる")
        void acceptsRealTableTokens() throws Exception {
            String html = mockMvc.perform(get("/dev/phone")
                            .param("path", "/t/" + seat.getAccessToken()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html).contains("/t/" + seat.getAccessToken());
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
