package jp.komeko.order.inventory.web;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * レシピの編集を Figma「ト09c レシピの編集」819:8679 に合わせる（2026-09-16）。
 *
 * <p>2026-09-14 の差分チェックで「中差」として挙げていた 3 件の決着です。
 * <ol>
 *   <li>原価の数字を stat カード 4 枚 → <b>1 本の帯</b>（{@code .statline}）</li>
 *   <li>行内「直す」ボタン（Figma は input＋✕ だけ）→ <b>表に 1 ボタン</b>にまとめた</li>
 *   <li>単価不明の警告を Figma の形（<b>食材を名指し</b>）に</li>
 * </ol>
 *
 * <p><b>2 番は「相談」扱いだった件です。</b>Figma は自動保存前提に見えますが、
 * このプロジェクトは JavaScript を使いません。行ごとにボタンを置くと Figma から遠く、
 * 自動保存にすると方針から外れる。<b>表に 1 つ</b>なら両方を満たせます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("レシピの編集が Figma ト09c どおり")
class RecipeEditMatchesFigmaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private InventoryTestFixture fixture;

    private MenuItem dish;
    private Ingredient cabbage;
    private Ingredient shiso;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        dish = fixture.createMenuItem("ト09cテスト用お好み焼き-" + suffix, 1180);
        cabbage = ingredients.save(new Ingredient("ト09cテスト用キャベツ-" + suffix, IngredientUnit.GRAM));
        // 仕入れ実績のない食材＝単価が分からない行を作るため
        shiso = ingredients.save(new Ingredient("ト09cテスト用大葉-" + suffix, IngredientUnit.PIECE));
        fixture.recordPurchase(cabbage, 1000, new BigDecimal("1000"), 8);
    }

    private String editUrl() {
        return "/inventory/recipes/" + dish.getId();
    }

    // ---------------------------------------------------------------- 数字の帯

    /**
     * ★ stat カード 4 枚 → 1 本の帯（Figma 819:8821）。
     *
     * <p>食材の詳細（ト04c）で作った {@code .statline} を使い回します。
     * 同じ「数字を並べて読ませる」用途なので部品を増やしません。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 原価は 1 本の帯（stat カード 4 枚をやめた）")
    void theCostNumbersSitInOneBand() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);

        String html = mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("帯になっていない").contains("statline");
        assertThat(html).as("stat カードが残っている").doesNotContain("stat__value");
        assertThat(html).contains("理論原価（税込）", "原価率（税込）", "税抜");
        // この 1 文がこの画面とエクセルの違いそのもの。消さないこと
        assertThat(html).contains("仕入れの単価が動くと、自動で追いかけます");
    }

    /**
     * 売価は見出しの補足にあるので、帯からは外しました。
     * 同じ数字を 2 か所に出すと、どちらを見ればいいか迷います。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 売価は帯に出さない（見出しの補足にある）")
    void thePriceIsNotRepeatedInTheBand() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);

        String html = mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int band = html.indexOf("statline");
        int bandEnd = html.indexOf("</div>", html.indexOf("statline__note"));
        assertThat(html.substring(band, bandEnd)).doesNotContain("売価");
        // 見出しの補足には残っている
        assertThat(html.substring(0, band)).contains("売価");
    }

    // ---------------------------------------------------------------- 表

    /**
     * ★ Figma の行は input と ✕ だけ（819:8846）。
     *
     * <p>行ごとの「直す」をやめ、表に 1 つの保存ボタンにまとめました。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 行ごとの「直す」が無く、表に 1 つの保存ボタンがある")
    void thereIsOneSaveButtonForTheWholeTable() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);
        recipeService.addLine(dish.getId(), shiso.getId(), new BigDecimal("3"), null);

        String html = mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("行ごとの「直す」が残っている").doesNotContain(">直す<");
        assertThat(html).as("まとめて保存するボタンが無い").contains("分量を保存する");
        assertThat(html).as("メモ列が残っている（Figma の表に無い）").doesNotContain("<th>メモ</th>");
    }

    /**
     * ★ 分量を打って Enter したときに、削除が走らないこと。
     *
     * <p>form の中で Enter を押すと<b>最初の submit が押されます</b>。
     * ✕ が先頭にあると、分量を直そうとして Enter した瞬間に行が消えます。
     * 先頭に隠しの保存ボタンを置いて防いでいます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ Enter で削除が走らない（先頭の submit は保存）")
    void pressingEnterSavesInsteadOfDeleting() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);

        String html = mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int form = html.indexOf("/quantities");
        int firstSubmit = html.indexOf("type=\"submit\"", form);
        int firstDelete = html.indexOf("/delete", form);

        assertThat(firstSubmit).as("フォームに submit が無い").isGreaterThan(0);
        assertThat(firstSubmit)
                .as("最初の submit が削除になっている（Enter で行が消える）")
                .isLessThan(firstDelete);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ まとめて保存すると、分量が全部入る")
    void savingUpdatesEveryQuantityAtOnce() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);
        recipeService.addLine(dish.getId(), shiso.getId(), new BigDecimal("3"), null);

        var lines = recipeService.linesOf(dish.getId());

        mockMvc.perform(post(editUrl() + "/quantities").with(csrf())
                        .param("qty_" + lines.get(0).getId(), "200")
                        .param("qty_" + lines.get(1).getId(), "5"))
                .andExpect(status().is3xxRedirection());

        var after = recipeService.linesOf(dish.getId());
        assertThat(after.get(0).getQtyPerItem()).isEqualByComparingTo("200");
        assertThat(after.get(1).getQtyPerItem()).isEqualByComparingTo("5");
    }

    /**
     * 0 や負の分量は在庫と原価を狂わせます。
     * 「登録できたのに数字が合わない」は気づく手掛かりが無いので、ここで止めます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 0 以下の分量は保存されない")
    void zeroOrNegativeQuantitiesAreRejected() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);
        Long lineId = recipeService.linesOf(dish.getId()).get(0).getId();

        mockMvc.perform(post(editUrl() + "/quantities").with(csrf())
                        .param("qty_" + lineId, "0"))
                .andExpect(status().is3xxRedirection());

        assertThat(recipeService.linesOf(dish.getId()).get(0).getQtyPerItem())
                .as("0 が保存されてしまった").isEqualByComparingTo("150");
    }

    // ---------------------------------------------------------------- 単価不明

    /**
     * ★ 単価が分からない食材を名指しすること（Figma 819:8916）。
     *
     * <p>「1 種類あります」だけだと、<b>どれを直せばいいか画面から分かりません</b>。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 単価が分からない食材を名前で言う")
    void theWarningNamesTheIngredient() throws Exception {
        recipeService.addLine(dish.getId(), cabbage.getId(), new BigDecimal("150"), null);
        recipeService.addLine(dish.getId(), shiso.getId(), new BigDecimal("3"), null);

        mockMvc.perform(get(editUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(shiso.getName())))
                .andExpect(content().string(containsString("は仕入れがまだ無く、単価が分かりません")))
                // 何をすれば直るかまで書く
                .andExpect(content().string(containsString("レシートを 1 枚登録する")))
                // 表の中の文言も Figma に合わせた
                .andExpect(content().string(containsString("単価が分かりません")))
                .andExpect(content().string(not(containsString("仕入れ未登録"))));
    }
}
