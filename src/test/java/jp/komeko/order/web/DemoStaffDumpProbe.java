package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店舗側の見学入口（/demo/staff）を測るために HTML へ落とすだけの道具。
 *
 * <p>この画面は {@code app.guest-login=true} のときしか出ません。
 * 開発用の起動は false なのでブラウザでは 404 になり、
 * 再起動して確かめるとログインが切れます。だからここで描かせます。
 *
 * <p>出力名を {@code _} で始めているのは、画面ダンプの掃除に巻き込まれないためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.guest-login=true")
@Transactional
@DisplayName("店舗側の見学入口を測るために書き出す")
class DemoStaffDumpProbe {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/demo/staff を HTML に落とす")
    void dump() throws Exception {
        String html = mockMvc.perform(get("/demo/staff"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        html = html.replace("href=\"/css/app.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("href=\"/css/app-[0-9a-f]+\\.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("src=\"/images/(.*?)-[0-9a-f]{32}\\.(\\w+)\"", "src=\"images/$1.$2\"")
                .replace("src=\"/images/", "src=\"images/");

        Path out = Path.of("target", "allscreens", "_demo-staff.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    /**
     * ゲストのボタンが出ている状態のログイン画面も落とす。
     *
     * <p>画面ダンプ（{@code AllScreensDumpTest}）は実店舗の設定で撮るので、
     * {@code s00-login.html} には<b>ゲストのボタンが写っていません</b>。
     * 公開デモのログイン画面を設計と突き合わせるには、こちらが要ります。
     */
    @Test
    @DisplayName("/login（ゲストのボタンつき）を HTML に落とす")
    void dumpLoginWithGuest() throws Exception {
        String html = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        html = html.replace("href=\"/css/app.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("href=\"/css/app-[0-9a-f]+\\.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("src=\"/images/(.*?)-[0-9a-f]{32}\\.(\\w+)\"", "src=\"images/$1.$2\"")
                .replace("src=\"/images/", "src=\"images/");

        Path out = Path.of("target", "allscreens", "_login-guest.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }
}
