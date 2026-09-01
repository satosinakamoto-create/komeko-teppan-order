package jp.komeko.order.inventory;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
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
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

    private static final Path OUT = Path.of("C:\\Users\\zaki\\AppData\\Local\\Temp\\claude"
            + "\\C--Users-zaki\\9065e85f-beb5-40d5-ba39-25977407c716\\scratchpad\\allscreens");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiningTableRepository tables;

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
        staff.put("s03-hall", "/hall");
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

            for (Map.Entry<String, String> page : Map.of(
                    "c02-menu", "/menu",
                    "c03-cart", "/cart",
                    "c04-bill", "/bill").entrySet()) {
                String html = mockMvc.perform(get(page.getValue()).session(session))
                        .andReturn().getResponse().getContentAsString();
                write(page.getKey(), html);
            }
        }

        // ── 税理士側 ──
        // デモの仕入れは先月ぶんが中心なので、データのある月を指定して撮る
        // （既定は当月。月初に開くと空に見えるのは仕様どおり）。
        String dataMonth = java.time.YearMonth.from(
                java.time.LocalDate.now().minusDays(20)).toString();
        Map<String, String> ledger = new LinkedHashMap<>();
        ledger.put("t01-summary", "/accountant");
        ledger.put("t02-tax", "/accountant/tax");
        ledger.put("t03-evidence", "/accountant/evidence");
        ledger.put("t04-journal", "/accountant/journal");
        ledger.put("t05-rules", "/accountant/rules");
        for (Map.Entry<String, String> page : ledger.entrySet()) {
            String html = mockMvc.perform(get(page.getValue())
                            .param("month", dataMonth)
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
