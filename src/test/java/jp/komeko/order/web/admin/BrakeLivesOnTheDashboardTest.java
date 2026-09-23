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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受付の非常ブレーキはダッシュボードにある（2026-09-19、店主の指摘）。
 *
 * <p>店主の言葉は「店舗設定の受付再開ボタンはダッシュボードか
 * 営業中につかうの所にあるべきじゃね？」。そのとおりです。
 *
 * <p>店舗設定は<b>一度決めたら触らない</b>設定の置き場で、混雑して手が回らなく
 * なった瞬間に開く場所ではありません。ダッシュボードはログイン後の最初の画面で、
 * 在席・進行中・提供待ちが並んでいる「いまの営業」の画面です。
 *
 * <h2>設計には答えがありませんでした</h2>
 * <p>Figma の ト00 ダッシュボードにも ト14 店舗設定にも、受付の操作は
 * 描かれていません（上の帯の「注文を受付中」表示だけ）。なので
 * 実装の都合——誰が押せるか、いつ押すか——で決めています。
 *
 * <h2>気をつけたこと</h2>
 * <ol>
 *   <li><b>ADMIN にだけ出す。</b>{@code /admin/**} の GET は STAFF も通りますが
 *       POST は ADMIN のみです。権限で隠さないと、スタッフには
 *       「押すと 403 になるボタン」が見えます</li>
 *   <li><b>押した場所へ戻す。</b>戻り先は許可した 2 つだけに丸めます。
 *       受け取った文字列をそのまま {@code redirect:} に渡すと、
 *       外のサイトへ飛ばせます（オープンリダイレクト）</li>
 *   <li><b>状態の大きなパネルは置かない。</b>2026-09-07 に決めた方針のままです。
 *       上の帯のピルと同じことを二度書きません。置いたのは操作だけで、
 *       状態はボタンの文言が兼ねます</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("受付のブレーキはダッシュボード")
class BrakeLivesOnTheDashboardTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path HOME =
            Path.of("src/main/resources/templates/admin/home.html");
    private static final Path SETTINGS =
            Path.of("src/main/resources/templates/admin/settings.html");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String body(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 店長のダッシュボードにブレーキが出ている")
    void theOwnerSeesTheBrakeOnTheDashboard() throws Exception {
        String html = mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("ダッシュボードにブレーキが無い。"
                + "混雑した瞬間に店舗設定を開かせることになる")
                .contains("/admin/settings/toggle-accepting");
        assertThat(html).as("戻り先の目印が無い。押すと店舗設定へ飛ばされる")
                .contains("name=\"from\"");
    }

    /**
     * ★ ふだんの運用では、スタッフはダッシュボードに入れない。
     *
     * <p>ここを確かめておかないと、次のテスト（権限で隠していること）が
     * 何を守っているのか分からなくなります。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("ふだんの運用ではスタッフはダッシュボードに入れない")
    void staffCannotReachTheDashboardNormally() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
    }

    /**
     * ★ それでも権限で隠すこと。<b>公開デモのときだけ状況が変わります。</b>
     *
     * <p>{@code app.guest-login.enabled} が有効なときだけ、見学者（ROLE_STAFF）に
     * {@code /admin/**} の <b>GET</b> が開きます（SecurityConfig）。
     * そのときダッシュボードは見えますが、POST は ADMIN のみのままです。
     * 隠さないと、見学者に「押すと 403 になるボタン」が見えます。
     *
     * <p>このテストは<b>テンプレートに権限の指定があること</b>を見ます。
     * デモ用のプロファイルを立ち上げずに、隠し忘れだけを捕まえられます。
     */
    @Test
    @DisplayName("★ ブレーキは権限で隠してある（公開デモで見学者に見せない）")
    void theBrakeIsHiddenByRole() throws Exception {
        String html = body(HOME);

        int brake = html.indexOf("/admin/settings/toggle-accepting");
        assertThat(brake).as("ダッシュボードにブレーキが無い").isGreaterThan(0);

        // ボタンを包んでいる箱（直前の <div class="card ...>）のタグを取り出して、
        // そのタグ自身に権限の指定があることを見る。
        // ★ 文字数でさかのぼらないこと。あいだに説明文が 3 つ入っていて、
        //   500 文字では箱まで届きませんでした（2026-09-19 に実際そうなりました）。
        // ★ "card" のうしろの空白まで含めて探すこと。空白を入れないと
        //   内側の card__body に部分一致して、そちらを掴みます（これも踏みました）。
        int open = html.lastIndexOf("<div class=\"card ", brake);
        assertThat(open).as("ブレーキを包む箱が見つからない").isGreaterThan(0);
        String tag = html.substring(open, html.indexOf('>', open) + 1);

        assertThat(tag)
                .as("ブレーキを包む箱に権限の指定が無い。公開デモのとき、見学者に"
                        + "押すと 403 になるボタンが見える。見つけたタグ: " + tag)
                .contains("sec:authorize=\"hasRole('ADMIN')\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ダッシュボードから押したら、ダッシュボードへ戻る")
    void pressingItFromTheDashboardComesBack() throws Exception {
        mockMvc.perform(post("/admin/settings/toggle-accepting").with(csrf()).param("from", "/admin"))
                .andExpect(redirectedUrl("/admin"));

        // 元に戻しておく（この切り替えは設定を実際に書き換えるため）
        mockMvc.perform(post("/admin/settings/toggle-accepting").with(csrf()).param("from", "/admin"))
                .andExpect(redirectedUrl("/admin"));
    }

    /**
     * ★ 戻り先は許可した 2 つだけ。
     *
     * <p>受け取った文字列をそのまま {@code redirect:} に渡すと、
     * {@code //example.com} のような値で外のサイトへ飛ばせます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 知らない戻り先は店舗設定へ丸める（外のサイトへ飛ばさない）")
    void anUnknownDestinationIsIgnored() throws Exception {
        mockMvc.perform(post("/admin/settings/toggle-accepting").with(csrf())
                        .param("from", "//example.com"))
                .andExpect(redirectedUrl("/admin/settings"));

        mockMvc.perform(post("/admin/settings/toggle-accepting").with(csrf()))
                .andExpect(redirectedUrl("/admin/settings"));
    }

    @Test
    @DisplayName("★ 店舗設定からは消えている（同じボタンが 2 か所にあると迷う）")
    void itIsGoneFromTheSettingsPage() throws Exception {
        assertThat(body(SETTINGS))
                .as("店舗設定にブレーキが残っている。同じ意味のボタンが 2 か所に並ぶ")
                .doesNotContain("toggle-accepting");
    }

    /**
     * ★ 畳んだチェックの説明が、消えたボタンを指していないこと。
     *
     * <p>「上の赤いボタンと同じ意味です」と書いたまま移すと、
     * 探しても見つからないものを探させることになります。
     */
    @Test
    @DisplayName("★ 畳んだチェックの説明が、無くなったボタンを指していない")
    void theFoldedHelpPointsSomewhereThatExists() throws Exception {
        String settings = body(SETTINGS);

        assertThat(settings).as("指し先の消えた説明が残っている")
                .doesNotContain("上の赤いボタン");
        assertThat(settings).as("どこにあるか書いていない")
                .contains("ダッシュボード");
    }

    /**
     * ★ 状態の大きなパネルは置かないまま（2026-09-07 の方針）。
     *
     * <p>受付中／停止中は上の帯のピルが全画面に出しています。
     * ここに再掲すると同じ情報が 2 か所に並ぶだけです。
     */
    @Test
    @DisplayName("★ 状態の再掲はしない（置いたのは操作だけ）")
    void onlyTheControlMovedNotTheStatusPanel() throws Exception {
        assertThat(body(HOME))
                .as("「現在：」で始まる状態の再掲が入っている。"
                        + "上の帯のピルと同じことを二度書かない")
                .doesNotContain("現在：");
    }
}
