package jp.komeko.order.inventory;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 全画面を HTML に書き出す、使い捨ての道具（テストではない）。
 *
 * <p>ログインの手間なしに「いまの画面はこうなっている」を人に見せるためのもの。
 * テンプレートも CSS も本番と同じものを通るので、モックアップではなく実物です。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:all-screens;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.demo-data=true",
        // 売上や仕入れの画面を「数字入り」で書き出すために、過去の帳簿も入れる。
        // ただし既定の 13 か月ぶんは書くのに 1 分かかるので、
        // 折れ線と前月比が成り立つ最小限（4 か月）にとどめる
        "app.demo-history-months=4",
        "app.seed-on-startup=true",
        "app.inventory.enabled=true",
        "app.backup.enabled=false",
        "spring.devtools.restart.enabled=false",
        "server.tomcat.accesslog.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DisplayName("全画面の書き出し")
class AllScreensDumpTest {

    /*
     * 書き出し先。target の下なので git には入らず、mvn clean で消える。
     *
     * ★ 以前は特定のセッションの一時フォルダを絶対パスで書いていた
     *   （...\9065e85f-...\scratchpad\allscreens）。そのフォルダは
     *   別の作業の残りもので、次に走らせた人には存在しません。
     *   書き出しても誰も見つけられない状態だったので、プロジェクトの中に移しました。
     */
    private static final Path OUT = Path.of("target", "allscreens");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiningTableRepository tables;

    @Autowired
    private MenuItemRepository menuItems;

    /** 会計後の画面（c04b）を撮るために、伝票を一時的に締める。 */
    @Autowired
    private jp.komeko.order.repository.TableSessionRepository tableSessions;

    @Autowired
    private jp.komeko.order.service.TableService tableService;

    @Test
    @DisplayName("店舗側とお客側の画面を全部 HTML に落とす")
    void dumpAll() throws Exception {
        Files.createDirectories(OUT);
        Files.createDirectories(OUT.resolve("css"));
        Files.copy(Path.of("src/main/resources/static/css/app.css"),
                OUT.resolve("css/app.css"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // ── 店舗側（ログインが要る画面） ──
        Map<String, String> staff = new LinkedHashMap<>();
        staff.put("s01-dashboard", "/admin");
        staff.put("s02-kitchen", "/kitchen");
        // s03-hall（ホール・会計）はここでは撮らない。
        // 「卓ごとの注文」は在席の卓が無いと何も写らないので、
        // 下でお客側の注文を作り終えてから撮る。
        staff.put("s04-stock", "/kitchen/stock");
        staff.put("s05-items", "/admin/items");
        staff.put("s06-categories", "/admin/categories");
        staff.put("s07-tables", "/admin/tables");
        staff.put("s08-qr", "/admin/qr");
        staff.put("s09-settings", "/admin/settings");
        staff.put("s10-staff", "/admin/staff");
        staff.put("s11-sales", "/admin/sales");
        staff.put("s12-orders", "/admin/orders");
        staff.put("s13-backups", "/admin/backups");
        staff.put("i01-purchases", "/inventory/purchases");
        staff.put("i02-purchase-new", "/inventory/purchases/new");
        staff.put("i03-ingredients", "/inventory/ingredients");
        staff.put("i04-ingredient-new", "/inventory/ingredients/new");
        staff.put("i05-recipes", "/inventory/recipes");
        staff.put("i06-tax-rates", "/inventory/tax-rates");

        for (Map.Entry<String, String> page : staff.entrySet()) {
            String html = mockMvc.perform(get(page.getValue()).with(user("店長").roles("ADMIN")))
                    .andReturn().getResponse().getContentAsString();
            write(page.getKey(), html);
        }

        // ── お客側（卓の QR から入る。セッションに卓が紐づく） ──
        DiningTable table = tables.findAll().stream().findFirst().orElse(null);
        if (table != null) {
            MockHttpSession session = new MockHttpSession();
            // /t/{token} を通すとセッションに卓が結びつく（QR を読んだのと同じ状態）
            String entry = mockMvc.perform(get("/t/" + table.getAccessToken()).session(session))
                    .andReturn().getResponse().getContentAsString();
            write("c01-table-entry", entry);

            //   人数を決めるまで伝票（TableSession）は開かない。
            //   開いていないと注文が作れず、伝票の画面が「お会計は完了しております」になる。
            mockMvc.perform(post("/t/" + table.getAccessToken() + "/start")
                    .session(session).with(csrf()).param("guestCount", "2"));

            write("c02-menu", mockMvc.perform(get("/menu").session(session))
                    .andReturn().getResponse().getContentAsString());

            // ★ カートと伝票は、空のまま撮ると「まだ何も選ばれていません」しか写らない。
            //   デザインの検討には中身が入った状態が要るので、ここで実際に注文を作る。
            //   作り話の HTML を置くのではなく、本物の画面を通した結果を撮るための手順。
            //   open-in-view: false なので、ここで getOptionGroups() を直接触ると
            //   LazyInitializationException になる（CLAUDE.md の JPA の項）。
            //   選択肢まで読んでくれる findByIdWithOptions を通してから判定する。
            List<MenuItem> orderable = menuItems.findVisibleForCustomer().stream()
                    .filter(MenuItem::isOrderable)
                    .map(m -> menuItems.findByIdWithOptions(m.getId()).orElse(null))
                    .filter(m -> m != null && m.getOptionGroups().isEmpty())
                    .limit(3)
                    .toList();
            for (MenuItem item : orderable) {
                mockMvc.perform(post("/cart/add").session(session).with(csrf())
                        .param("menuItemId", String.valueOf(item.getId()))
                        .param("quantity", item.equals(orderable.get(0)) ? "2" : "1"));
            }
            // ★ カートに品が入った状態のメニューも撮る。
            //   下に貼り付くカートバーは「注文リストに品があるとき」しか出ないので、
            //   空のメニュー（c02）だけを見ていると、バーが出ているつもりで
            //   一度も確かめないまま進んでしまう。
            //   ここが隠れると、最後の 1 品がバーに隠れる不具合にも気づけない。
            write("c02b-menu-with-cart", mockMvc.perform(get("/menu").session(session))
                    .andReturn().getResponse().getContentAsString());

            write("c03-cart", mockMvc.perform(get("/cart").session(session))
                    .andReturn().getResponse().getContentAsString());

            // 注文すると「ご注文を承りました」（設計 暗07）へ飛ぶ。
            // リダイレクト先をそのまま辿って撮る。行き先を決め打ちで書くと、
            // 遷移先を変えたときに古い画面を撮り続けることになる。
            String placedUrl = mockMvc.perform(post("/checkout").session(session).with(csrf()))
                    .andReturn().getResponse().getRedirectedUrl();
            write("c03b-order-placed", mockMvc.perform(get(placedUrl).session(session))
                    .andReturn().getResponse().getContentAsString());

            write("c04-bill", mockMvc.perform(get("/bill").session(session))
                    .andReturn().getResponse().getContentAsString());

            // ★ 会計後の「ご来店ありがとうございました」。
            //   締めてから同じ /bill を開くと、この画面に切り替わる。
            //   ここを撮っておかないと、会計後にお客さまの手元へ何が残るのかを
            //   誰も見ないまま進むことになる（実際その状態だった）。
            //   撮り終えたら会計を取り消して、他の画面の前提を壊さない。
            // ★ この画面を見ているブラウザがついている卓を締めること。
            //   「最初に見つかった開いている伝票」だと、デモは 6 卓が同時に開いて
            //   いるので別の卓を締めることになり、/bill は伝票のまま返ってくる。
            //   同じバイト数のファイルが 2 つできて気づいた。
            Long closedId = tableSessions.findOpenSessionIds(table.getId()).stream()
                    .findFirst().orElse(null);
            if (closedId != null) {
                tableService.closeSession(closedId, false, "撮影", null,
                        jp.komeko.order.domain.SettlementMethod.CASH);
                write("c04b-thanks", mockMvc.perform(get("/bill").session(session))
                        .andReturn().getResponse().getContentAsString());
                tableService.reopenSession(closedId, "撮影");
            }

            // ★ サービスの画面（設計 暗03）。
            //   タイルを 1 つ押してから撮る。押した結果どうなるかまで見ないと、
            //   「押せそうに見えるだけ」の画面かどうかが分からない。
            //   お水は ¥0 の注文として厨房ボードへ、
            //   スタッフを呼ぶは呼び出しとしてホール画面へ飛ぶ（通り道が違う）。
            mockMvc.perform(post("/service/call/STAFF").session(session).with(csrf()));
            write("c05-service", mockMvc.perform(get("/service").session(session))
                    .andReturn().getResponse().getContentAsString());

            System.out.println("  お客側: カートに " + orderable.size() + " 品入れて注文を 1 件作った");
        }

        // ── ホール・会計（在席の卓ができてから撮る） ──
        // 上の店舗側ループと一緒に撮ると、まだ誰も座っていないので
        // 「卓ごとの注文」が空の画面になってしまう。
        // 注文が入った状態の画面でないと、余白も行の詰まり具合も確かめられない。
        write("s03-hall", mockMvc.perform(get("/hall").with(user("店長").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString());

        // ── 税理士側 ──
        // 月を指定せずに撮る。既定が前月になったので、これが
        // 税理士が開いた瞬間に見る画面そのものになる。
        // （以前はここで月を明示していた。既定が当月で、そのままだと
        //   空の画面が撮れてしまったため。回避策のほうを直した。）
        Map<String, String> ledger = new LinkedHashMap<>();
        ledger.put("t01-summary", "/accountant");
        ledger.put("t02-tax", "/accountant/tax");
        ledger.put("t03-evidence", "/accountant/evidence");
        ledger.put("t04-journal", "/accountant/journal");
        ledger.put("t05-rules", "/accountant/rules");
        for (Map.Entry<String, String> page : ledger.entrySet()) {
            String html = mockMvc.perform(get(page.getValue())
                            .with(user("税理士").roles("ACCOUNTANT")))
                    .andReturn().getResponse().getContentAsString();
            write(page.getKey(), html);
        }

        // ── ログイン画面（誰でも見る入口） ──
        write("s00-login", mockMvc.perform(get("/login"))
                .andReturn().getResponse().getContentAsString());

        System.out.println("書き出し先: " + OUT);
    }

    /**
     * ファイルとして開けるよう、絶対パスとハッシュ付きの参照を相対に直す。
     *
     * <p>CSS だけは更新時刻を問い合わせ文字列に付ける。
     * 本番は Spring が中身のハッシュを URL に入れてくれるので気にしなくてよいが、
     * ここは <code>css/app.css</code> という固定の名前になるため、
     * ブラウザが前回の中身を使い回す。<b>app.css を直したのに古い見た目のまま撮れてしまい、
     * 「効いていない」と誤って判断した</b>ので、名前を変わるようにしてある。
     */
    private void write(String name, String html) throws Exception {
        String cssUrl = "css/app.css?v=" + Files.getLastModifiedTime(
                Path.of("src/main/resources/static/css/app.css")).toMillis();
        html = html.replaceAll("href=\"/css/app-[0-9a-f]+\\.css\"", "href=\"" + cssUrl + "\"")
                .replace("href=\"/css/app.css\"", "href=\"" + cssUrl + "\"")
                .replaceAll("src=\"/js/([^\"]*?)-[0-9a-f]{32}\\.js\"", "src=\"js/$1.js\"")
                .replace("src=\"/js/", "src=\"js/")
                .replace("href=\"/images/", "href=\"images/")
                .replace("src=\"/images/", "src=\"images/");
        Files.writeString(OUT.resolve(name + ".html"), html, StandardCharsets.UTF_8);
        System.out.println("  " + name + " (" + html.length() + " 文字)");
    }
}
