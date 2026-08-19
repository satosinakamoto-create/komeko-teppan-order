package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ポートフォリオのデモ用「ゲストで参加する」のテスト。
 *
 * <p><b>このテストが守っているもの＝権限の境界</b><br>
 * 公開デモは、名前も分からない人が触る画面です。
 * 見てほしいのは「注文が厨房に届き、状態を進めるとお客さまの画面が変わる」ところだけで、
 * メニューの値段・売上・スタッフのパスワードまで触れる必要はありません。
 *
 * <p>境界が崩れても<b>画面上は何も変わらない</b>（ゲストで入れてしまう、が増えるだけ）ので、
 * 目で見て気づくことができません。だからテストで固定します。
 */
class GuestLoginTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "app.guest-login=true")
    @DisplayName("ゲストログインを有効にしたとき（公開デモの設定）")
    class Enabled {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        DiningTableRepository tableRepository;

        @Test
        @DisplayName("ログイン画面に「ゲストで参加する」が出る")
        void buttonIsShown() throws Exception {
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("ゲストで参加する")));
        }

        @Test
        @DisplayName("ボタンを押すと厨房ボードへ入れる")
        void guestCanEnterKitchen() throws Exception {
            mockMvc.perform(post("/login/guest").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/kitchen"));
        }

        @Test
        @DisplayName("CSRF トークンが無いと拒否される（外部サイトから踏ませられない）")
        void withoutCsrfIsRejected() throws Exception {
            // GET のリンクにすると、外部サイトに <img src=".../login/guest"> と
            // 書かれるだけで意図せずログイン状態にされる。POST + CSRF で塞いでいる。
            mockMvc.perform(post("/login/guest"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET では入れない（POST だけを受け付ける）")
        void getIsNotAllowed() throws Exception {
            mockMvc.perform(get("/login/guest"))
                    .andExpect(status().is4xxClientError());
        }

        @ParameterizedTest(name = "{0} は見られる")
        @ValueSource(strings = {"/kitchen", "/hall", "/admin", "/admin/qr", "/admin/sales", "/admin/orders"})
        @WithMockUser(roles = "STAFF")
        @DisplayName("見せると決めた画面は開ける")
        void guestCanRead(String path) throws Exception {
            // QR コードの画面がいちばん大事。卓名・QR・URL がここに全部載っていて、
            // 「どうやって注文が始まるのか」がこの画面だけで伝わる。
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0} は見られない")
        @ValueSource(strings = {"/admin/items", "/admin/categories", "/admin/tables",
                                "/admin/settings", "/admin/staff", "/admin/backups"})
        @WithMockUser(roles = "STAFF")
        @DisplayName("値段・設定・スタッフ・バックアップは開けない")
        void guestCannotReadSensitivePages(String path) throws Exception {
            // 特に /admin/staff は他人のパスワードを変えられる画面。
            // /admin/settings を触られると、税率や深夜料金が変わってデモが壊れる。
            mockMvc.perform(get(path)).andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} への書き込みは拒否される")
        @ValueSource(strings = {"/admin/tables/1/regenerate", "/admin/tables/1/delete",
                                "/admin/orders/1/cancel", "/admin/settings"})
        @WithMockUser(roles = "STAFF")
        @DisplayName("見られる画面でも、書き換えはできない（GET だけ開けている）")
        void guestCannotWrite(String path) throws Exception {
            // ここが崩れると、見学者が QR を作り直して
            // 「席に貼ってある QR が読めない」状態にできてしまう。
            mockMvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @DisplayName("サイドバーにダッシュボードの入口が出る")
        void dashboardLinkIsShown() throws Exception {
            // 画面は開けるのにサイドバーに出ていない、という状態を作りがち。
            // 入口が無ければ「無い」のと同じで、全体像が伝わらない。
            // アクセス許可（SecurityConfig）と入口（サイドバー）は対で確かめる。
            mockMvc.perform(get("/kitchen"))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("ダッシュボード")));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @DisplayName("QR の画面から、お客さま画面をそのまま開ける")
        void qrPageLinksToCustomerScreen() throws Exception {
            // カメラで QR を読めるのは、印刷して席に貼ってあるときの話。
            // 画面を見ている人は目の前の QR を自分で読めないので、
            // 押せば開けるリンクが無いとお客さま側へ辿り着けない。
            //
            // リンクは卓ごとに出るので、卓が 1 つも無いと何も出ません
            // （最初これを忘れて、空の画面を相手にテストが落ちました）。
            tableRepository.save(new DiningTable("デモ卓", 4, 10));

            mockMvc.perform(get("/admin/qr"))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("この卓のお客さま画面を開く")));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @DisplayName("厨房ボードの操作はできる（これが無いと連携を体感できない）")
        void guestCanOperateKitchen() throws Exception {
            // あえて許している唯一の書き込み。
            // 押してもらえないと「状態を進めるとお客さまの画面が変わる」が伝わらない。
            // 存在しない注文なので結果は 3xx（画面へ戻る）で、403 にならないことを見る。
            mockMvc.perform(post("/kitchen/orders/999999/status")
                            .with(csrf())
                            .param("status", "COOKING"))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("既定（実店舗の設定）")
    class DisabledByDefault {

        @Autowired
        MockMvc mockMvc;

        @Test
        @DisplayName("ログイン画面に「ゲストで参加する」は出ない")
        void buttonIsHidden() throws Exception {
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("ゲストで参加する"))));
        }

        @Test
        @DisplayName("経路そのものが存在しない（ボタンを隠すだけでは不十分）")
        void endpointDoesNotExist() throws Exception {
            // 画面から消すだけだと、URL を直接叩かれれば通ってしまう。
            // @ConditionalOnProperty でコントローラごと作らないので 404 になる。
            mockMvc.perform(post("/login/guest").with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("生存確認（スリープ防止の ping）")
    class Ping {

        @Autowired
        MockMvc mockMvc;

        @Test
        @DisplayName("ログインなしで ok を返す")
        void pingIsPublic() throws Exception {
            // ログインが要ると、叩くたびにログイン画面が描画されて
            // 「軽い入口」の意味が無くなる。
            mockMvc.perform(get("/ping"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok"));
        }
    }
}
