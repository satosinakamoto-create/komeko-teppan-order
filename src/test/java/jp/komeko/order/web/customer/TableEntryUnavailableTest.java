package jp.komeko.order.web.customer;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 使えない QR を読んだお客さまへの案内（2026-09-07 の全体点検 #2）。
 *
 * <p><b>何が問題だったか</b><br>
 * 点検では「500 になる」と報告したが、それは<b>誤り</b>だった。
 * {@code GlobalExceptionHandler} が両例外を 404／400 の共通ページ
 * {@code error/message} に変換していて、文言も表示されていた。
 * 残っていた本当の問題は 3 つ：
 * <ul>
 *   <li>共通ページは無地レイアウト（layout/plain）で、席に着いた
 *       お客さまが見る画面としてはトンマナが違う</li>
 *   <li>「メニューに戻る」ボタンが付くが、卓に紐づいていないので
 *       押しても注文まで進めず、余計に迷わせる行き止まりだった</li>
 *   <li>利用停止卓はタイトルが「ご注文を承れませんでした」——
 *       まだ何も注文していない人への文言としては文脈がずれていた</li>
 * </ul>
 *
 * <p><b>なぜ大事か</b><br>
 * QR を作り直すと、貼り替え前の旧 QR は必ずこの道を通る。
 * 席に着いた直後のお客さまが最初に見る画面になり得る。
 *
 * <p>ステータスの決め：無効トークンは 404（もう存在しない入口）、
 * 利用停止卓は 200 の案内（席は実在する。使えない理由を伝える画面が本体）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("使えない QR の案内")
class TableEntryUnavailableTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DiningTableRepository diningTableRepository;

    @Test
    @DisplayName("★ 無効なトークン（旧 QR）は 500 ではなく 404 の案内ページ")
    void staleQrGetsAGuidePage() throws Exception {
        String html = mockMvc.perform(get("/t/kono-token-ha-sonzai-shinai"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("customer/table-unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("この QR は現在使われていません");
        assertThat(html).contains("スタッフにお声がけください");
    }

    @Test
    @DisplayName("★ 利用停止の卓は、例外に書いてある案内文がそのまま画面に出る")
    void inactiveTableShowsItsMessage() throws Exception {
        // 卓名は 20 文字制限（@Size）。nanoTime を付けると溢れるので短く刻む
        DiningTable table = new DiningTable(
                "停止卓" + (System.nanoTime() % 1_000_000), 4, 999);
        table.setActive(false);
        table = diningTableRepository.save(table);

        String html = mockMvc.perform(get("/t/" + table.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/table-unavailable"))
                .andReturn().getResponse().getContentAsString();

        // TableService.getByAccessToken が持っている文言。
        // お客さま向けに書かれたのに一度も表示されていなかった
        assertThat(html).contains("この席はただいまご利用いただけません。スタッフにお声がけください");

        diningTableRepository.delete(table);
    }

    @Test
    @DisplayName("★ 人数決定（POST /t/{token}/start）でも旧 QR は案内ページになる")
    void staleQrOnStartAlsoGuides() throws Exception {
        // 人数画面を開いたまま QR が再発行された、の道。GET だけ塞いでも
        // 「決定」を押した瞬間に 500 になる
        mockMvc.perform(post("/t/kono-token-mo-nai/start")
                        .param("guestCount", "2")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("customer/table-unavailable"));
    }

    @Test
    @DisplayName("案内ページはお客さま側のレイアウトで描かれる")
    void guidePageUsesCustomerLayout() throws Exception {
        // スタッフ画面のトンマナ（theme-desk）が混ざっていないこと
        String html = mockMvc.perform(get("/t/nai-token"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("theme-desk");
        assertThat(html).doesNotContain("staff-shell");
    }
}
