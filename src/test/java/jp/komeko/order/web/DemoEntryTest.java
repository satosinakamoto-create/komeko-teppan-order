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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
        @DisplayName("卓がまだ無いとき（起動直後）は、エラーではなく自動更新の準備中ページが出る")
        void showsPreparingPageWhileSeederIsStillRunning() throws Exception {
            // コールドスタートでは、HTTP の受付開始から DemoDataSeeder の完了までに
            // 数十秒の空白がある。以前はこの間の /demo が
            // 409「DataSeeder が動いているか確認してください」という
            // 開発者向けの行き止まりだった（2026-08-22 に本番で再現）。
            // ポートフォリオの主導線なので、待てば勝手に開く画面を返す。
            mockMvc.perform(get("/demo"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("demo-preparing"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("デモを準備しています")))
                    // 5 秒ごとに /demo を開き直す（卓が入り次第そのまま注文画面へ）
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("refresh")));
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
        @DisplayName("お客さま画面に「QR を読んだ直後」であることの前置きが出る")
        void customerScreenExplainsTheQrPremise() throws Exception {
            // 実店舗では、席の QR を読んだ人がこの画面を開く。
            // つまり自分がどこの席にいるか分かった状態で始まる。
            //
            // 公開デモはポートフォリオのボタンから直接ここへ来るので、
            // その前提が無いまま、いきなり人数を聞かれる。
            // 「なぜ人数を聞かれるのか」が分からないと、その先へ進んでもらえない。
            DiningTable table = tableRepository.save(new DiningTable("カウンター1", 2, 10));

            mockMvc.perform(get("/t/" + table.getAccessToken()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("席に貼ってある QR コード")));
        }

        @Test
        @DisplayName("店舗側の見学入口はログインなしで開ける")
        void staffEntryIsPublic() throws Exception {
            // ポートフォリオから 1 クリックで来る入口。
            // ログインを要求すると、そこで止まってしまう。
            mockMvc.perform(get("/demo/staff"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("厨房ボードへ進む")));
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
        @DisplayName("店舗側の入口はキャッシュされない")
        void staffEntryIsNotCacheable() throws Exception {
            // 中に CSRF トークンが埋まっている画面なので、保存されると困る。
            //
            // ★ このヘッダは Spring Security が既定で付けています。自分では書きません。
            //   一度自分で no-store を付けたら、Spring Security が
            //   Cache-Control / Pragma / Expires の 3 つをまとめて飛ばしてしまい、
            //   足したつもりが既定より弱くなっていました（本番のヘッダを見て気づいた）。
            //   誰が付けるかは変わりうるので、「付いていること」だけを固定します。
            mockMvc.perform(get("/demo/staff"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Cache-Control",
                                    org.hamcrest.Matchers.containsString("no-store")));
        }

        @Test
        @DisplayName("勝手に進まない（人が押すまで待つ）")
        void staffEntryNeverSubmitsByItself() throws Exception {
            // ★ もとは開いた瞬間にフォームを送信していた。
            //   押していないのに厨房ボードが出るので、
            //   何が起きたのか分からないまま画面が切り替わる。
            //   見学モードであることも、データが架空であることも伝わらない。
            //
            //   1 クリック減らすより、何の画面に入るのかを先に伝えるほうが大事。
            //   自動送信が戻ってくると説明を読む時間が消えるので、ここで止める。
            for (String url : new String[]{"/demo/staff", "/demo/staff?retry=1"}) {
                String html = mockMvc.perform(get(url))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

                assertThat(html)
                        .as("%s に自動送信を書かない", url)
                        .doesNotContain(".submit()");
                assertThat(html)
                        .as("%s で押せるボタンを出す", url)
                        .contains("厨房ボードへ進む");
            }
        }

        @Test
        @DisplayName("入る前に、見学モードであることと架空データであることを伝える")
        void staffEntryExplainsWhatYouAreAbout() throws Exception {
            // 入ってから探させない。この 1 画面で分かるようにする。
            String html = mockMvc.perform(get("/demo/staff"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(html)
                    .as("何の画面かを先に言う")
                    .contains("厨房のタブレット");
            assertThat(html)
                    .as("触れる範囲を先に言う")
                    .contains("見学モード")
                    .contains("保存や削除はできません");
            assertThat(html)
                    .as("実在の店の数字だと誤解させない")
                    .contains("架空");
        }

        @Test
        @DisplayName("やり直しの案内は、失敗して戻ってきたときだけ出す")
        void retryNoticeOnlyOnRetry() throws Exception {
            assertThat(mockMvc.perform(get("/demo/staff?retry=1"))
                    .andReturn().getResponse().getContentAsString())
                    .contains("前の画面が古くなっていたため");

            assertThat(mockMvc.perform(get("/demo/staff"))
                    .andReturn().getResponse().getContentAsString())
                    .as("普通に来た人に、起きていない失敗の話をしない")
                    .doesNotContain("前の画面が古くなっていたため");
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
    @Transactional
    @DisplayName("実店舗の設定のとき（既定）")
    class DisabledByDefault {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        DiningTableRepository tableRepository;

        @Test
        @DisplayName("お客さま画面にデモの前置きは出ない")
        void customerScreenHasNoDemoNote() throws Exception {
            // ★ ここが本番。
            //   前置きは公開デモのための文章で、実店舗のお客さまには意味が無い。
            //   むしろ「架空のデータです」と書いてあるものを
            //   本物の注文画面で見せることになる。
            //
            //   出す条件を間違えても画面は普通に動いてしまうので、
            //   気づけるのは店頭でお客さまが見たときになる。だから固定する。
            DiningTable table = tableRepository.save(new DiningTable("3番テーブル", 4, 10));

            mockMvc.perform(get("/t/" + table.getAccessToken()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("席に貼ってある QR コード"))))
                    .andExpect(content().string(
                            // 文言を変えても効くように「架空」だけで見る。
                            // 前は「架空のデータ」で見ていたが、言い回しを直した瞬間に
                            // 何も確かめないテストになるところだった。
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("架空"))));
        }

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
