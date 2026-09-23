package jp.komeko.order.web;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
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
 * 見学入口（/demo）を測るために HTML へ落とすだけの道具。
 *
 * <p>実装を設計と突き合わせるには描いたものが要りますが、
 * この画面は {@code app.guest-login=true} のときしか出ません。
 * 開発用の起動は false なので、ブラウザでは 404 になります。
 * 再起動して確かめるとログインが切れるため、ここで描かせます。
 *
 * <p>出力名を {@code _} で始めているのは、画面ダンプの掃除に巻き込まれないためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.guest-login=true")
@Transactional
@DisplayName("見学入口を測るために書き出す")
class DemoGateDumpProbe {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DiningTableRepository tableRepository;

    @Test
    @DisplayName("/demo を HTML に落とす")
    void dump() throws Exception {
        tableRepository.save(new DiningTable("カウンター1", 2, 10));

        String html = mockMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 画面ダンプと同じ書き換え。単体で開けるようにする
        html = html.replace("href=\"/css/app.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("href=\"/css/app-[0-9a-f]+\\.css\"", "href=\"css/app.css?v=probe\"")
                .replaceAll("src=\"/images/(.*?)-[0-9a-f]{32}\\.(\\w+)\"", "src=\"images/$1.$2\"")
                .replace("src=\"/images/", "src=\"images/");

        Path out = Path.of("target", "allscreens", "_demo-guest.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }
}
