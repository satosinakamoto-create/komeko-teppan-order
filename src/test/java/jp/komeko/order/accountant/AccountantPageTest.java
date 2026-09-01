package jp.komeko.order.accountant;

import jp.komeko.order.accountant.service.AccountantService;
import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.service.PurchaseDraft;
import jp.komeko.order.inventory.service.PurchaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 税理士の画面のテスト。
 *
 * <p><b>ここで守るもの</b>
 * <ol>
 *   <li>権限の境界 — 税理士に店舗運営が見えず、スタッフに帳簿が見えないこと</li>
 *   <li>消費税の集計が「税率 × 控除区分」で分かれること（申告に直結する）</li>
 *   <li>仕訳 CSV が弥生の 25 項目であること（1 個でも欠けると取り込めない）</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("税理士の画面")
class AccountantPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private AccountantService accountantService;

    /** 経過措置（登録番号なし）の仕入れを 1 枚作る。 */
    private Purchase recordWithoutRegistration(LocalDate on, int amount) {
        return purchaseService.record(new PurchaseDraft(
                on, on, "テスト青果店-" + System.nanoTime(), amount, PaymentMethod.CASH,
                null, EvidenceType.NOT_QUALIFIED, null, null, null, false,
                List.of(new PurchaseDraft.LineDraft(
                        "テスト野菜", null, amount, 8, null,
                        PurchaseCategory.FOOD, null, null, false))), null);
    }

    // ========================================================================
    //  権限の境界
    // ========================================================================

    @Test
    @DisplayName("ログインしていなければ入れない")
    void requires_login() throws Exception {
        mockMvc.perform(get("/accountant"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ スタッフには帳簿が見えない（原価も控除率も全部見える画面なので）")
    void staff_cannot_see_the_ledger() throws Exception {
        mockMvc.perform(get("/accountant")).andExpect(status().isForbidden());
        mockMvc.perform(get("/accountant/tax")).andExpect(status().isForbidden());
        mockMvc.perform(get("/accountant/journal")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 税理士には店舗運営の画面が見えない（卓・商品・スタッフ）")
    void accountant_cannot_see_store_operations() throws Exception {
        mockMvc.perform(get("/admin/tables")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/items")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/staff")).andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen")).andExpect(status().isForbidden());
        // 在庫の画面も店の作業なので見せない
        mockMvc.perform(get("/inventory/ingredients")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("店長は税理士画面も見られる（何を渡しているか確認できる必要がある）")
    void admin_can_see_the_ledger() throws Exception {
        mockMvc.perform(get("/accountant")).andExpect(status().isOk());
    }

    // ========================================================================
    //  画面が描けること
    // ========================================================================

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("5 つの画面が全部描ける")
    void all_pages_render() throws Exception {
        mockMvc.perform(get("/accountant"))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/index"));
        mockMvc.perform(get("/accountant/tax"))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/tax"));
        mockMvc.perform(get("/accountant/evidence"))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/evidence"));
        mockMvc.perform(get("/accountant/journal"))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/journal"));
        mockMvc.perform(get("/accountant/rules"))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/rules"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("店舗管理のサイドバーから税理士の画面へ行ける")
    void staff_sidebar_links_to_accountant() throws Exception {
        // 画面を作っても入口が無ければ誰も辿り着けない。
        // 実際、この導線が無いあいだは URL を直接打つ以外に開く方法が無く、
        // 「画面が見当たらない」状態になっていた。
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("税理士の画面")))
                .andExpect(content().string(containsString("href=\"/accountant\"")));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("月を指定しないと前月を開く（当月だと月初に空の画面を見せてしまう）")
    void defaults_to_previous_month() throws Exception {
        String previous = YearMonth.now().minusMonths(1).toString();

        mockMvc.perform(get("/accountant"))
                .andExpect(status().isOk())
                // 対象月の表示にも、前後の月へ動くリンクにも前月が入る
                .andExpect(content().string(containsString(previous)));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("記録が 1 件も無くても壊れない")
    void renders_when_empty() throws Exception {
        mockMvc.perform(get("/accountant?month=2019-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("今月のまとめ")));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("証憑の詳細が描ける（画像と入力値を並べる画面）")
    void evidence_detail_renders() throws Exception {
        Purchase purchase = recordWithoutRegistration(LocalDate.now(), 1080);

        mockMvc.perform(get("/accountant/evidence/" + purchase.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("accountant/evidence-detail"))
                .andExpect(content().string(containsString("記録されている内容")))
                .andExpect(content().string(containsString("税理士の確認")));
    }

    // ========================================================================
    //  消費税の集計
    // ========================================================================

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 消費税は「税率 × 控除区分」で分かれる（同じ8%でも控除できる額が違う）")
    void tax_breakdown_splits_by_deduction() {
        LocalDate day = LocalDate.now().minusDays(600);
        YearMonth month = YearMonth.from(day);

        // 登録番号なし＝経過措置。控除率は取引日のマスタから決まる
        Purchase purchase = recordWithoutRegistration(day, 1080);
        int deduction = purchase.getDeductionRatePercent();

        List<AccountantService.TaxRow> rows = accountantService.taxBreakdown(month);
        assertThat(rows).isNotEmpty();

        AccountantService.TaxRow row = rows.stream()
                .filter(r -> r.getTaxRatePercent() == 8 && r.getDeductionRatePercent() == deduction)
                .findFirst()
                .orElseThrow();

        assertThat(row.getGrossAmount()).isEqualTo(1080);
        // 控除できる税額は「税額 × 控除率」。全額控除なら税額そのもの
        assertThat(row.getDeductibleTax())
                .isEqualTo(row.getTaxAmount() * deduction / 100);
    }

    // ========================================================================
    //  仕訳の書き出し
    // ========================================================================

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 仕訳CSVが弥生の25項目になっている（1個でも欠けると取り込めない）")
    void journal_csv_has_exactly_25_columns() throws Exception {
        LocalDate day = LocalDate.now().minusDays(610);
        recordWithoutRegistration(day, 2160);

        byte[] body = mockMvc.perform(get("/accountant/journal/export.csv")
                        .param("month", YearMonth.from(day).toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();

        // BOM（無いと日本語 Excel が文字化けする）
        assertThat(body[0]).isEqualTo((byte) 0xEF);

        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = text.replace("﻿", "").split("\r\n");
        assertThat(lines).isNotEmpty();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // カンマ区切りで 25 項目。末尾が空でも数に入るよう -1 を指定する
            assertThat(line.split(",", -1))
                    .as("弥生は 25 項目そろっていない行を取り込まない: %s", line)
                    .hasSize(25);
        }

        // 識別フラグ（1列目）は仕訳を表す 2110
        assertThat(lines[0]).startsWith("2110,");
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("経過措置の仕入れは、摘要に「◯%控除対象」が入る（帳簿への記載義務）")
    void journal_summary_notes_the_transitional_rate() throws Exception {
        LocalDate day = LocalDate.now().minusDays(620);
        Purchase purchase = recordWithoutRegistration(day, 3240);

        String text = new String(mockMvc.perform(get("/accountant/journal/export.csv")
                                .param("month", YearMonth.from(day).toString()))
                        .andReturn().getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);

        if (purchase.getDeductionRatePercent() < 100) {
            assertThat(text).contains(purchase.getDeductionRatePercent() + "%控除対象");
        }
    }

    // ========================================================================
    //  確認の記録
    // ========================================================================

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 税理士が書けるのは「確認した」だけ（店の数字は直せない）")
    void accountant_can_only_record_a_check() throws Exception {
        Purchase purchase = recordWithoutRegistration(LocalDate.now(), 540);
        assertThat(purchase.getAccountantCheckedAt()).isNull();

        mockMvc.perform(post("/accountant/evidence/" + purchase.getId() + "/checked").with(csrf())
                        .param("note", "この支出の内容を店主に確認"))
                .andExpect(status().is3xxRedirection());

        Purchase after = accountantService.find(purchase.getId());
        assertThat(after.getAccountantCheckedAt()).isNotNull();
        assertThat(after.getAccountantNote()).isEqualTo("この支出の内容を店主に確認");

        // 店の記録（金額）は一切変わっていない
        assertThat(after.getTotalAmount()).isEqualTo(540);

        // 仕入れを直す口は税理士側に存在しない
        mockMvc.perform(post("/inventory/purchases/" + purchase.getId() + "/delete").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("登録番号のない仕入れは「判断が要るもの」に出る")
    void missing_registration_number_is_flagged() {
        LocalDate day = LocalDate.now().minusDays(630);
        recordWithoutRegistration(day, 800);

        List<AccountantService.Attention> attentions =
                accountantService.attentions(YearMonth.from(day));
        assertThat(attentions).anyMatch(a -> a.label().contains("登録番号"));
    }

    // ========================================================================
    //  文字の大きさ（この画面の要求）
    // ========================================================================

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 見た目は店舗管理と同じ（SnowUI）で、文字寸法だけ上乗せする")
    void uses_snow_theme_plus_ledger_sizes() throws Exception {
        // 見た目を増やすと「同じシステムなのに画面ごとに別物」に見えるので、
        // 管理系はスタッフ側と同じ SnowUI に揃える。
        // .theme-ledger が引き受けるのは寸法（最小16px・中20px・大24px）だけ。
        // どちらか片方でも外れると、色が浮くか文字が小さくなるので両方を固定する。
        mockMvc.perform(get("/accountant"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("theme-snow theme-ledger")));
    }
}
