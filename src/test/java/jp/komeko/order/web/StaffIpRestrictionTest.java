package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * スタッフ画面の「接続元 IP 制限」のテスト。
 *
 * <p><b>なぜテストするのか</b><br>
 * この機能の失敗モードは 2 方向あり、どちらも実害が大きい。
 * <ul>
 *   <li>閉めるべきものが開いている → お客さまの端末からログイン画面が見える（守れていない）</li>
 *   <li>開けるべきものが閉まっている → スタッフが締め出される／お客さまが注文できない（営業が止まる）</li>
 * </ul>
 * 両方向を必ず確かめる。
 *
 * <p>MockMvc では {@code setRemoteAddr} で「どの IP から来たか」を偽装できるので、
 * 実際にネットワークを組まなくても関所の挙動を検証できる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // このテストだけ制限を有効化する（192.168.1.x だけ許可）
        "app.staff-access.allowed-ips[0]=192.168.1.0/24"
})
class StaffIpRestrictionTest {

    @Autowired
    private MockMvc mockMvc;

    /** 指定した IP から来たことにするヘルパー。 */
    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    // ── 閉まっているべき方向 ─────────────────────────────────────

    @Test
    @DisplayName("許可外のIPからはログイン画面すら表示されない（403）")
    void loginPageIsHiddenFromUnknownIp() throws Exception {
        mockMvc.perform(from(get("/login"), "192.168.9.9"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("許可外のIPからは、たとえ認証済みでも厨房画面を開けない（403）")
    @WithMockUser(roles = "ADMIN")
    void kitchenIsBlockedEvenWithValidRole() throws Exception {
        // パスワード（認証）と接続元（IP）は独立した錠であることの確認。
        // どちらか片方を突破しただけでは入れない。
        mockMvc.perform(from(get("/kitchen"), "192.168.9.9"))
                .andExpect(status().isForbidden());
    }

    // ── 開いているべき方向 ───────────────────────────────────────

    @Test
    @DisplayName("許可した範囲のIPからはログイン画面が開ける")
    void loginPageOpensFromAllowedIp() throws Exception {
        mockMvc.perform(from(get("/login"), "192.168.1.21"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("サーバPC自身（localhost）は設定に関係なく常に開ける（閉め出し防止の非常口）")
    void localhostIsAlwaysAllowed() throws Exception {
        // MockMvc の既定の接続元は 127.0.0.1
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("お客さま用のページは許可外のIPからでも普通に開ける（注文を止めない）")
    void customerPagesAreNotAffected() throws Exception {
        // 制限の対象はスタッフゾーンだけ。
        // ここを間違えると「セキュリティ強化したら注文が止まった」になる。
        mockMvc.perform(from(get("/"), "192.168.9.9"))
                .andExpect(status().isOk());
    }
}
