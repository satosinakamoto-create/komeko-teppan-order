package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.TaxRatePeriod;
import jp.komeko.order.inventory.service.TaxRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 税率・控除率マスタ画面のテスト。
 *
 * <p><b>このテストが守っているもの＝「制度が変わっても壊れない」という約束の最後の一歩</b><br>
 * 税率をデータで持つ設計にしても、<b>その行を足す手段が無ければ絵に描いた餅</b>です。
 * 2026-08-30 に、まさにその画面が設計から抜け落ちていたことが分かりました。
 *
 * <p>ここでは特に「<b>古い行が消えないこと</b>」を固定します。
 * 過去の仕入れはその行を根拠に計算されているので、
 * 消してしまうと去年の帳簿を計算し直したときに答えが変わります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("税率・控除率マスタの画面")
class InventoryTaxRatePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaxRuleService taxRuleService;

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("スタッフには開けない（誤操作の被害が大きいのでADMIN限定）")
    void staff_cannot_open() throws Exception {
        mockMvc.perform(get("/inventory/tax-rates"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("一覧が描ける（初期データが並ぶ）")
    void list_renders() throws Exception {
        mockMvc.perform(get("/inventory/tax-rates"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/tax-rates"))
                .andExpect(content().string(containsString("軽減_飲食料品")))
                // 経過措置は2031年の終了分まで入っている
                .andExpect(content().string(containsString("2031-10-01")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("改定を登録すると、前の行が閉じて新しい行が増える（消えない）")
    void adding_closes_previous_row_without_deleting() throws Exception {
        int before = taxRuleService.allTaxRates().size();

        // 飲食料品 1%（2027-04-01〜）が成立した、という想定。
        mockMvc.perform(post("/inventory/tax-rates/tax").with(csrf())
                        .param("rateClass", TaxRatePeriod.CLASS_REDUCED_FOOD)
                        .param("ratePercent", "1")
                        .param("validFrom", "2027-04-01")
                        .param("note", "テスト用の改定"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/tax-rates"));

        // ★ 行は増える。減らない。
        assertThat(taxRuleService.allTaxRates()).hasSize(before + 1);

        // 施行日の前日までは元の 8%、施行日からは新しい 1%
        assertThat(taxRuleService.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, LocalDate.of(2027, 3, 31)))
                .isEqualTo(8);
        assertThat(taxRuleService.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, LocalDate.of(2027, 4, 1)))
                .isEqualTo(1);

        // ★ 過去が動いていないこと。ここが本丸。
        assertThat(taxRuleService.taxRateOn(TaxRatePeriod.CLASS_REDUCED_FOOD, LocalDate.of(2026, 8, 30)))
                .isEqualTo(8);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("入力に不備があれば登録せず描き直す")
    void rejects_invalid_input() throws Exception {
        int before = taxRuleService.allTaxRates().size();

        mockMvc.perform(post("/inventory/tax-rates/tax").with(csrf())
                        .param("rateClass", TaxRatePeriod.CLASS_STANDARD)
                        .param("ratePercent", "999")     // 100を超える
                        .param("validFrom", "2027-04-01"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/tax-rates"));

        assertThat(taxRuleService.allTaxRates()).hasSize(before);
    }
}
