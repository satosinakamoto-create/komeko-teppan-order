package jp.komeko.order.web;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ポートフォリオの QR 用の入口（{@code /demo}）のテスト。
 *
 * <p><b>何を守っているか</b><br>
 * 卓の URL に入っているトークンは、卓を作り直すたびに変わります。
 * 公開デモは再起動のたびに DB ごと作り直されるので、
 * <b>卓の URL をそのまま QR にしてサイトに貼ると翌朝には死にます。</b>
 * {@code /demo} はその間に挟む、変わらない名前です。
 *
 * <p>この入口が壊れても、壊れたことに気づけるのは
 * 「サイトの QR を読んだ人がエラー画面を見たとき」です。
 * こちらからは見えないので、テストで固定します。
 */
class DemoEntryTest {

    /*
     * ★ @Transactional と、卓の消去について
     *
     *   このクラスは app.guest-login=true という設定が GuestLoginTest と同じなので、
     *   Spring はテスト用コンテキストを使い回します。DB も同じものです。
     *   つまり、こちらが何も置かなくても<b>他のテストが作った卓が残っています</b>。
     *
     *   実際、最初はそれで落ちました。「カウンター1 を優先する」を確かめたいのに、
     *   別のテストが先に作った卓が毎回返ってきていたのです。
     *   このとき厄介なのは、テスト自身は正しく、原因が自分の外にあることです。
     *
     *   そこで各テストの前に卓を空にし、@Transactional で終了時に巻き戻します。
     *   自分が汚さず、他人にも汚されない、という両方が要ります。
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "app.guest-login=true")
    @Transactional
    @DisplayName("公開デモの設定のとき")
    class Enabled {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        DiningTableRepository tableRepository;

        @Autowired
        TableService tableService;

        @BeforeEach
        void clearTables() {
            tableRepository.deleteAll();
        }

        @Test
        @DisplayName("ログイン不要で、卓のお客さま画面へ転送される")
        void redirectsToATable() throws Exception {
            tableRepository.save(new DiningTable("カウンター1", 2, 10));

            mockMvc.perform(get("/demo"))
                    .andExpect(status().is3xxRedirection())
                    // 転送先は /t/{36文字のトークン}
                    .andExpect(redirectedUrlPattern("/t/*"));
        }

        @Test
        @DisplayName("転送先は、いま実在する卓のトークンである")
        void redirectsToALiveToken() throws Exception {
            // ★ ここが本題。
            // 「どこかへ転送された」だけでは、古いトークンを返していても通ってしまう。
            // いま DB にある卓のトークンと一致することまで見る。
            DiningTable table = tableRepository.save(new DiningTable("カウンター1", 2, 10));

            MvcResult result = mockMvc.perform(get("/demo")).andReturn();
            String location = result.getResponse().getRedirectedUrl();

            assertThat(location).isEqualTo("/t/" + table.getAccessToken());
        }

        @Test
        @DisplayName("撮影用に空けてある卓（カウンター1）を優先する")
        void prefersTheStageTable() throws Exception {
            // 名前順では「カウンター1」より前に来る卓をわざと先に作る。
            // 単純に「最初の卓」を返す実装だと、この test は落ちる。
            tableRepository.save(new DiningTable("あああ席", 4, 5));
            DiningTable stage = tableRepository.save(new DiningTable("カウンター1", 2, 10));

            MvcResult result = mockMvc.perform(get("/demo")).andReturn();

            assertThat(result.getResponse().getRedirectedUrl())
                    .as("DemoDataSeeder が空けている卓と揃っていないと、"
                            + "見学者がいきなり相席から始まる")
                    .isEqualTo("/t/" + stage.getAccessToken());
        }

        @Test
        @DisplayName("優先卓が埋まっていたら、空いている別の卓へ案内する")
        void fallsBackToAFreeTable() throws Exception {
            DiningTable stage = tableRepository.save(new DiningTable("カウンター1", 2, 10));
            DiningTable other = tableRepository.save(new DiningTable("テーブル1", 4, 20));
            tableService.openSession(stage.getId(), 2);

            MvcResult result = mockMvc.perform(get("/demo")).andReturn();

            assertThat(result.getResponse().getRedirectedUrl())
                    .isEqualTo("/t/" + other.getAccessToken());
        }

        @Test
        @DisplayName("店舗側の見学入口はログインなしで開ける")
        void staffEntryIsPublic() throws Exception {
            // ポートフォリオから 1 クリックで来る入口。
            // ログインを要求すると、そこで止まってしまう。
            mockMvc.perform(get("/demo/staff"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("店舗側の画面へ進む")));
        }

        @Test
        @DisplayName("店舗側の入口は GET でログインさせない（POST のフォームを置くだけ）")
        void staffEntryDoesNotLogInOnGet() throws Exception {
            // ★ ここが本題。
            //   GET で直接ログインさせると、外部サイトに <img src="…/demo/staff"> と
            //   書かれるだけで、見た人が意図せずログイン状態になる。
            //   ゲストログインを POST ＋ CSRF にしているのは、それを塞ぐため。
            //   入口を増やすために、その塞ぎ穴を開け直していないことを固定する。
            String html = mockMvc.perform(get("/demo/staff"))
                    .andExpect(status().isOk())          // リダイレクトしない
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .as("POST のフォームで /login/guest へ送ること")
                    .contains("method=\"post\"")
                    .contains("/login/guest");
            assertThat(html)
                    .as("CSRF トークンが埋まっていること")
                    .contains("_csrf");
        }

        @Test
        @DisplayName("CSRF が切れても 403 で終わらせず、やり直せる画面に戻す")
        void staleTokenGoesBackToTheEntry() throws Exception {
            // /demo/staff は開いた瞬間に POST を送るページ。
            // 戻るボタンやページ復元で古い HTML が出てくると、
            // 期限切れのトークンで送信され 403 の画面で終わる。
            // ポートフォリオから来た人にとって、そこが行き止まりになる。
            mockMvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.post("/login/guest"))   // トークン無し＝切れた状態
                    .andExpect(status().is3xxRedirection())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .redirectedUrl("/demo/staff?retry=1"));
        }

        @Test
        @DisplayName("やり直しの画面では自動送信しない（往復が止まらなくなるため）")
        void retryScreenDoesNotAutoSubmit() throws Exception {
            String html = mockMvc.perform(get("/demo/staff").param("retry", "1"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .as("ボタンは残す（人が押せば進める）")
                    .contains("店舗側の画面へ進む");
            assertThat(html)
                    .as("自動送信のスクリプトは出さない")
                    .doesNotContain("staffEntryForm');");
        }

        @Test
        @DisplayName("見学者の保存操作は 403 のまま（救済の対象を広げない）")
        void guestWriteStillForbidden() throws Exception {
            // 上の救済は /login/guest の POST だけに絞っている。
            // ほかの 403 まで拾うと、権限が無いことを権限の問題として伝えられなくなる。
            mockMvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.post("/admin/settings")
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user("guest").roles("STAFF")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("QR 画像がログインなしで取れる")
        void qrImageIsPublic() throws Exception {
            // ポートフォリオサイトに貼る画像。ここがログインを要求すると、
            // サイトに貼った QR が「画像が出ない」形で壊れる。
            // しかも壊れて見えるのは訪問者の画面だけで、こちらからは気づけない。
            byte[] png = mockMvc.perform(get("/demo/qr.png"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(png).isNotEmpty();
            // PNG のシグネチャ（\x89 P N G）。中身が本当に画像かどうかまで見る
            assertThat(png[0] & 0xFF).isEqualTo(0x89);
            assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("PNG");
        }

        @Test
        @DisplayName("全部埋まっていてもエラーにしない（相席になるだけ）")
        void neverFailsWhenEverythingIsBusy() throws Exception {
            // 実店舗でも、同じ卓の QR を 2 人が読めば同じ伝票に入る。
            // それがこのシステムの仕様（1卓1伝票）なので、
            // 見学者が同時に来たとき片方だけ門前払いにするほうが不自然。
            DiningTable stage = tableRepository.save(new DiningTable("カウンター1", 2, 10));
            tableService.openSession(stage.getId(), 2);

            mockMvc.perform(get("/demo"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("/t/*"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("実店舗の設定のとき（既定）")
    class DisabledByDefault {

        @Autowired
        MockMvc mockMvc;

        @Test
        @DisplayName("経路そのものが存在しない")
        void endpointDoesNotExist() throws Exception {
            // 画面から隠すだけでは、URL を知っている人には通ってしまう。
            // @ConditionalOnProperty でコントローラごと作らないので 404 になる。
            mockMvc.perform(get("/demo"))
                    .andExpect(status().isNotFound());
        }
    }
}
