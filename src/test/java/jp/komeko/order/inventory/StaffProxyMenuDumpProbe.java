package jp.komeko.order.inventory;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ★ スタッフが代理で注文するときの「お客さまの画面」を撮るための道具。
 *
 * <p>店主の指摘「何番卓の注文を受け付けてるかお品書きの所で常にフッターに
 * 出しておいた方がいいかも」を検討するために作りました。
 *
 * <p>実装にはすでに {@code layout/customer.html} の {@code .staffbar} があり、
 * {@code staffMode} が真のときだけ出ます。ところが
 * {@code AllScreensDumpTest} が撮っている {@code /menu} は<b>お客さまとして</b>
 * 開いたものなので、この帯が 1 枚も残っていませんでした。
 *
 * <p>ゲスト（見学）権限では {@code /staff/order} が 403 になるため、
 * ダンプと同じくモックのスタッフで通します。本物のパスワードは使いません。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:staff-proxy;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.demo-data=true",
        "app.demo-history-months=0",
        "app.seed-on-startup=true",
        "app.inventory.enabled=true",
        "app.backup.enabled=false",
        "spring.devtools.restart.enabled=false",
        "server.tomcat.accesslog.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DisplayName("道具：スタッフが代理で注文するときの画面を撮る")
class StaffProxyMenuDumpProbe {

    private static final Path OUT = Path.of("target", "allscreens");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiningTableRepository tables;

    @Autowired
    private TableSessionRepository tableSessions;

    @Test
    @DisplayName("番号の盤面 → 卓を開く → お品書き・注文リストを撮る")
    void dumpStaffProxyScreens() throws Exception {
        // ★ セッションを 1 つ通して使うこと。TableContext（@SessionScope）に
        //   「いまどの卓の代理で入れているか」が入り、それが staffMode の元になります。
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/staff/order")
                .with(user("店長").roles("ADMIN")).session(session));

        // 伝票が開いている卓を選ぶ。空席だと人数を聞く画面で止まり、
        // お品書きまで進まないため
        Long tableId = tables.findAll().stream()
                .filter(t -> !tableSessions.findOpenSessionIds(t.getId()).isEmpty())
                .map(DiningTable::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("伝票が開いている卓がありません"));

        mockMvc.perform(get("/staff/order/seats/" + tableId)
                .with(user("店長").roles("ADMIN")).session(session));

        for (String[] page : new String[][]{
                {"/menu", "x01-staff-proxy-menu"},
                {"/cart", "x02-staff-proxy-cart"},
                {"/bill", "x03-staff-proxy-bill"}}) {
            String html = mockMvc.perform(get(page[0])
                            .with(user("店長").roles("ADMIN")).session(session))
                    .andReturn().getResponse().getContentAsString();
            write(page[1], html);
            System.out.println("PROBE " + page[1] + " staffbar="
                    + (html.contains("staffbar") ? "あり" : "なし")
                    + " 長さ=" + html.length());
        }
    }

    /** ダンプと同じく、CSS・JS・画像の参照を相対に直してから書き出す。 */
    private void write(String name, String html) throws Exception {
        String cssUrl = "css/app.css?v=" + Files.getLastModifiedTime(
                Path.of("src/main/resources/static/css/app.css")).toMillis();
        html = html.replaceAll("href=\"/css/app-[0-9a-f]+\\.css\"", "href=\"" + cssUrl + "\"")
                .replace("href=\"/css/app.css\"", "href=\"" + cssUrl + "\"")
                .replaceAll("src=\"/js/([^\"]*?)-[0-9a-f]{32}\\.js\"", "src=\"js/$1.js\"")
                .replace("src=\"/js/", "src=\"js/")
                .replaceAll("(src|href)=\"/images/([^\"]*?)-[0-9a-f]{32}(\\.[a-z]+)\"",
                        "$1=\"images/$2$3\"")
                .replace("src=\"/images/", "src=\"images/")
                .replace("href=\"/images/", "href=\"images/");
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }
}
