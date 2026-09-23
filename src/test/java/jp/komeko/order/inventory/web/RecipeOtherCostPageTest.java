package jp.komeko.order.inventory.web;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * その他材料費の画面（2026-09-16）。
 *
 * <p>サービス層の約束は {@code RecipeOtherCostTest} が見ています。
 * ここは<b>画面から実際に入れられるか</b>だけを見ます。
 * 描画テストを書くのは、{@code open-in-view: false} なので
 * テンプレートが関連を辿ると描画時に落ちるためです。
 * 単体テストが通っていても画面が 500 になることがあります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("その他材料費の画面")
class RecipeOtherCostPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private InventoryTestFixture fixture;

    private MenuItem takoyaki;

    @BeforeEach
    void setUp() {
        takoyaki = fixture.createMenuItem("画面テスト用たこ焼き-" + System.nanoTime(), 400);
    }

    private String editUrl() {
        return "/inventory/recipes/" + takoyaki.getId();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ レシピ編集画面に その他材料費 の入力欄がある")
    void theFormIsOnThePage() throws Exception {
        mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("その他材料費")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("name=\"amountIncludingTax\"")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 画面から保存でき、元の画面へ戻る（PRG）")
    void savingRedirectsBack() throws Exception {
        mockMvc.perform(post(editUrl() + "/other-cost").with(csrf())
                        .param("amountIncludingTax", "40")
                        .param("memo", "ソース・青のり"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(editUrl()));

        assertThat(recipeService.otherCostOf(takoyaki.getId()).getAmountIncludingTax())
                .isEqualTo(40);
    }

    /**
     * ★ 食材を 1 つも登録していない商品でも、画面が描けて金額が出ること。
     *
     * <p>店主の要望そのものの経路です。材料の表が空のまま
     * その他材料費の行だけを出すので、テンプレートの分岐が合っていないと
     * ここで落ちます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 食材ゼロでも画面が描けて、原価が出る")
    void theScreenRendersWithNoIngredients() throws Exception {
        recipeService.setOtherCost(takoyaki.getId(), 180, "全部まとめて");

        mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                // 理論原価（税込）の欄に 180 が出る
                .andExpect(content().string(org.hamcrest.Matchers.containsString("¥180")))
                // 内訳にも行として出る（合計だけ変わって出どころが分からない、を防ぐ）
                .andExpect(content().string(org.hamcrest.Matchers.containsString("全部まとめて")));
    }

    /**
     * ★ 原価表の一覧で「原価は出ているのに材料列が未登録」にならないこと。
     *
     * <p>{@code RecipeCost.isEmpty()} はレシピ行が 0 件かどうかを見ているので、
     * その他材料費だけ入れた商品は「未登録」と出てしまいます。
     * 原価が ¥180 と出ている隣に「未登録」と書いてあったら、
     * どちらが本当なのか読む人には分かりません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧で「原価は出ているのに未登録」にならない")
    void theListDoesNotSayUnregisteredWhenACostExists() throws Exception {
        recipeService.setOtherCost(takoyaki.getId(), 180, "全部まとめて");

        String html = mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // この商品の行だけを切り出す
        int at = html.indexOf(takoyaki.getName());
        assertThat(at).as("商品が一覧に出ていない").isGreaterThan(0);
        int end = html.indexOf("</tr>", at);
        String row = html.substring(at, end);

        assertThat(row).as("原価が出ているのに「未登録」と書いてある").doesNotContain("未登録");
        assertThat(row).as("原価が出ているのにボタンが「登録する」").doesNotContain("登録する");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 空欄で送ると外れる")
    void blankRemovesIt() throws Exception {
        recipeService.setOtherCost(takoyaki.getId(), 180, null);

        mockMvc.perform(post(editUrl() + "/other-cost").with(csrf())
                        .param("amountIncludingTax", "")
                        .param("memo", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(recipeService.otherCostOf(takoyaki.getId())).isNull();
    }

    /**
     * マイナスはサービス層が投げます。投げっぱなしにするとエラー画面になるので、
     * コントローラで捕まえて画面のメッセージに変えていること。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ マイナスを送ってもエラー画面にならず、画面に戻る")
    void negativeShowsAMessageInsteadOfAnErrorPage() throws Exception {
        mockMvc.perform(post(editUrl() + "/other-cost").with(csrf())
                        .param("amountIncludingTax", "-100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(editUrl()));

        assertThat(recipeService.otherCostOf(takoyaki.getId())).isNull();
    }

    /**
     * ★ 原価は ADMIN と GUEST にしか見せていません（既存の作り）。
     * その他材料費の行も同じ扱いにしないと、金額だけ STAFF に漏れます。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ STAFF には その他材料費の金額を出さない（原価は隠す既存の作りに合わせる）")
    void staffDoesNotSeeTheAmount() throws Exception {
        recipeService.setOtherCost(takoyaki.getId(), 180, "秘密の金額");

        String html = mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("STAFF に原価の金額が見えている").doesNotContain("¥180");
    }
}
