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

    @Autowired
    private jp.komeko.order.inventory.service.PurchaseService purchases;

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
        //
        // ★ id をベタ書きしないこと。
        //   以前 "/inventory/purchases/1" と書いていて、ほかのテストが先に
        //   仕入れを作ると別の記録を開いてしまい、全体を流したときだけ落ちた。
        //   単体では通るので原因が分かるまで時間を食う類い。
        //   いま保存したものを名前で引き当てる。
        Long savedId = purchases.search(null, null, null, null, "テスト商店", false,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().orElseThrow().getId();

        mockMvc.perform(get("/inventory/purchases/" + savedId))
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

    /**
     * 2026-08-31 の全体チェックで直した分の回帰テスト。
     * どれも「税理士に見せた瞬間に信用を失う」類いのずれだったもの。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("全体チェック（2026-08-31）の回帰")
    class FinalAuditRegressions {

        @org.springframework.beans.factory.annotation.Autowired
        private jp.komeko.order.inventory.service.PurchaseService purchaseService;

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("期限超過のレシートは、同等確認しても「保管してください」と言う")
        void overdue_receipt_keeps_paper_after_check() throws Exception {
            // 受領から 10 日後に登録 → 入力期限（7日）超過 → 紙の保管が必要
            jp.komeko.order.inventory.domain.Purchase overdue = purchaseService.record(
                    new jp.komeko.order.inventory.service.PurchaseDraft(
                            LocalDate.now().minusDays(10), LocalDate.now().minusDays(10),
                            "期限切れテスト店", 500,
                            jp.komeko.order.inventory.domain.PaymentMethod.CASH,
                            null, jp.komeko.order.inventory.domain.EvidenceType.NOT_QUALIFIED,
                            null, null, null, false,
                            java.util.List.of(new jp.komeko.order.inventory.service.PurchaseDraft.LineDraft(
                                    "テスト品", null, 500, 8, null,
                                    jp.komeko.order.inventory.domain.PurchaseCategory.FOOD,
                                    null, null, false))), null);
            org.assertj.core.api.Assertions.assertThat(overdue.isPaperRetentionRequired()).isTrue();

            // 詳細画面のフォームにも「破棄して構いません」が出ないこと
            mockMvc.perform(get("/inventory/purchases/" + overdue.getId()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(containsString("破棄して構いません"))));

            // 確認ボタンを押しても「保管してください」と言うこと
            mockMvc.perform(post("/inventory/purchases/" + overdue.getId() + "/checked").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("flashSuccess",
                            containsString("保管してください")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("手入力の確認画面は「インボイスなし」が既定（適格簡易ではない）")
        void manual_defaults_to_not_qualified() throws Exception {
            // 手入力＝登録番号なし・合計不明の状態。先頭の「適格簡易請求書（全額控除）」が
            // 既定のまま保存されるのがいちばん危ない。
            mockMvc.perform(post("/inventory/purchases/manual").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(
                            "value=\"NOT_QUALIFIED\" selected")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("確認画面に、印字された税額の入力欄がある")
        void confirm_form_has_printed_tax_field() throws Exception {
            // 「印字された値を保存する」（設計2章）の入口。
            // 欄が無いと purchase_line.tax_amount がどの経路でも埋まらない。
            mockMvc.perform(post("/inventory/purchases/manual").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("lines[0].taxAmount")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("検索結果を CSV でダウンロードできる（UTF-8 + BOM）")
        void csv_export_works() throws Exception {
            purchaseService.record(
                    new jp.komeko.order.inventory.service.PurchaseDraft(
                            LocalDate.now(), LocalDate.now(), "CSVテスト商店", 1080,
                            jp.komeko.order.inventory.domain.PaymentMethod.CASH,
                            null, jp.komeko.order.inventory.domain.EvidenceType.NOT_QUALIFIED,
                            null, null, null, false,
                            java.util.List.of(new jp.komeko.order.inventory.service.PurchaseDraft.LineDraft(
                                    "CSV用の品", null, 1080, 8, null,
                                    jp.komeko.order.inventory.domain.PurchaseCategory.FOOD,
                                    null, null, false))), null);

            byte[] body = mockMvc.perform(get("/inventory/purchases/export.csv"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("text/csv")))
                    .andReturn().getResponse().getContentAsByteArray();

            // BOM（EF BB BF）で始まること。無いと日本語 Excel が文字化けする
            org.assertj.core.api.Assertions.assertThat(body[0]).isEqualTo((byte) 0xEF);
            org.assertj.core.api.Assertions.assertThat(body[1]).isEqualTo((byte) 0xBB);
            org.assertj.core.api.Assertions.assertThat(body[2]).isEqualTo((byte) 0xBF);

            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            org.assertj.core.api.Assertions.assertThat(text).contains("CSVテスト商店");
            org.assertj.core.api.Assertions.assertThat(text).contains("取引年月日");
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @DisplayName("原価率はスタッフには見えない（ADMIN と公開デモの見学者だけ）")
        void cost_ratio_hidden_from_staff() throws Exception {
            // 設計6章の初期案どおり。打ち合わせ⑥で見直すまでは見せない側に倒す。
            mockMvc.perform(get("/inventory/purchases"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(containsString("実際原価率"))));

            mockMvc.perform(get("/inventory/recipes"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(containsString("原価率"))));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("原価率は店長には見える")
        void cost_ratio_visible_to_admin() throws Exception {
            mockMvc.perform(get("/inventory/purchases"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("実際原価率")));
        }
    }
}
