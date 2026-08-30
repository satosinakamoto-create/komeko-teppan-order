package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 仕入れ・経費の画面を、実際に<b>描画まで</b>確認するテスト。
 *
 * <p><b>なぜ描画までやるのか</b><br>
 * Thymeleaf の式はコンパイルされません。プロパティ名を間違えても、
 * record のアクセサに {@code ()} を付け忘れても、ビルドは通ります。
 * サービスの単体テストも素通りします。<b>その画面を一度でも本当に描かせる</b>まで、
 * 誤りは見つかりません（既存の {@code HallBillPageTest} と同じ考え方です）。
 *
 * <p><b>{@code @Transactional} を付けない理由</b><br>
 * 本番は {@code open-in-view: false} で、画面を描く時点では DB 接続がありません。
 * テストに {@code @Transactional} を付けるとその状況を再現できず、
 * 「テストは通るのに本番で LazyInitializationException」を見逃します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("仕入れ・経費の画面")
class InventoryPurchasePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("ログインしていなければ入れない")
    void requires_login() throws Exception {
        mockMvc.perform(get("/inventory/purchases"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("一覧が描ける（記録ゼロでも壊れない）")
    void list_renders_when_empty() throws Exception {
        // 売上も仕入れも 0 件のとき、原価率は 0 で割ることになる。
        // ここで 0% と表示すると嘘になるので「—」を出す作りにしてある。
        mockMvc.perform(get("/inventory/purchases"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/purchases"))
                .andExpect(content().string(containsString("実際原価率（税抜）")))
                .andExpect(content().string(containsString("検索")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("登録の入口が描ける")
    void new_page_renders() throws Exception {
        mockMvc.perform(get("/inventory/purchases/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/purchase-form"))
                .andExpect(content().string(containsString("レシートを撮る")))
                .andExpect(content().string(containsString("手で入力する")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("手入力の確認画面が描ける（明細の入力欄が並ぶ）")
    void manual_confirm_renders() throws Exception {
        mockMvc.perform(post("/inventory/purchases/manual").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/purchase-form"))
                .andExpect(content().string(containsString("lines[0].itemText")))
                .andExpect(content().string(containsString("見比べて")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("保存すると一覧と詳細に出る（登録から表示までひと通り）")
    void save_and_show() throws Exception {
        LocalDate today = LocalDate.now();

        // ── 保存 ──
        // スーパーのレシートを想定して、食材（軽減8%）と消耗品（標準10%）を混ぜる。
        // 1 枚の中で税率が分かれるのが実際なので、そこを通しておく。
        mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("purchasedOn", today.toString())
                        .param("storeName", "テスト商店")
                        .param("totalAmount", "766")
                        .param("paymentMethod", "CASH")
                        .param("evidenceType", "SIMPLIFIED_INVOICE")
                        .param("registrationNumber", "T7000012050002")
                        .param("equivalenceChecked", "true")
                        .param("lines[0].itemText", "キャベツ")
                        .param("lines[0].amount", "216")
                        .param("lines[0].taxRatePercent", "8")
                        .param("lines[0].category", "FOOD")
                        .param("lines[1].itemText", "洗剤")
                        .param("lines[1].amount", "550")
                        .param("lines[1].taxRatePercent", "10")
                        .param("lines[1].category", "SUPPLIES")
                        // 予備の空行は保存されないこと（画面には常に並んでいる）
                        .param("lines[2].itemText", "")
                        .param("lines[2].category", "FOOD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/purchases"));

        // ── 一覧に出る ──
        mockMvc.perform(get("/inventory/purchases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("テスト商店")))
                // 食材だけが原価の分子（216 円。洗剤の 550 円は入らない）
                .andExpect(content().string(containsString("216")));

        // ── 詳細に出る ──
        mockMvc.perform(get("/inventory/purchases/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/purchase-detail"))
                .andExpect(content().string(containsString("キャベツ")))
                .andExpect(content().string(containsString("洗剤")))
                // 登録番号は検算に通るので「検算OK」が出る
                .andExpect(content().string(containsString("検算OK")))
                // 同等確認済みなので、紙を捨ててよい状態
                .andExpect(content().string(containsString("確認済み")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("明細が 1 行も無ければ保存させない")
    void rejects_empty_lines() throws Exception {
        mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("purchasedOn", LocalDate.now().toString())
                        .param("storeName", "テスト商店")
                        .param("totalAmount", "1000")
                        .param("paymentMethod", "CASH")
                        .param("evidenceType", "NOT_QUALIFIED"))
                .andExpect(status().isOk())   // リダイレクトせず描き直す
                .andExpect(view().name("inventory/purchase-form"))
                .andExpect(content().string(containsString("明細を1行以上入力してください")));
    }
}
