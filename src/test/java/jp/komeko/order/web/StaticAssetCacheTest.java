package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 静的ファイル（CSS・JS・画像）がキャッシュされることを固定するテスト。
 *
 * <p><b>なぜ要るのか</b><br>
 * 公開デモを実測したとき、{@code app.css} にこれが付いていました。
 *
 * <pre>Cache-Control: no-cache, no-store, max-age=0, must-revalidate</pre>
 *
 * <p>Spring Security が<b>全レスポンスに</b>付ける既定のヘッダです。
 * ログイン後の画面が戻るボタンで見えてしまうのを防ぐためのもので、
 * HTML には正しい。けれど静的ファイルにも同じものが乗るため、
 * 画面を移動するたびに CSS も JS もロゴも取り直していました。
 *
 * <p><b>手元では絶対に気づけません。</b>
 * 往復 8ms の localhost では取り直しの代償がゼロに見えるからです。
 * 往復 150ms の回線で初めて体感の重さになります。
 * 実測しなければ分からなかった種類の問題なので、テストで固定します。
 *
 * <p><b>ハッシュ付き URL も一緒に確かめる理由</b><br>
 * 「1 年キャッシュさせる」だけだと、CSS を直しても
 * 戻ってきた人の画面が変わりません。中身から作ったハッシュを
 * ファイル名に混ぜて初めて、長いキャッシュが安全になります。
 * 片方だけでは意味がないので、2 つで 1 組として検査します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("静的ファイルはキャッシュさせる")
class StaticAssetCacheTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("CSS に no-store が付かず、長期キャッシュが指定される")
    void cssIsCacheable() throws Exception {
        MvcResult result = mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andReturn();

        String cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);

        assertThat(cacheControl)
                .as("Spring Security の既定ヘッダが静的ファイルにも乗ると、"
                        + "画面を移動するたびに CSS を取り直すことになる")
                .isNotNull()
                .doesNotContain("no-store")
                .contains("max-age=");
    }

    @Test
    @DisplayName("画面の CSS 参照が、内容ハッシュ付きの URL に書き換わる")
    void cssUrlIsFingerprinted() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();

        // 例: /css/app-9f3c1b7e….css
        assertThat(html)
                .as("ハッシュが付かないまま長期キャッシュすると、"
                        + "CSS を直しても戻ってきた人の画面が変わらない")
                .containsPattern("/css/app-[0-9a-f]{8,}\\.css");
    }
}
