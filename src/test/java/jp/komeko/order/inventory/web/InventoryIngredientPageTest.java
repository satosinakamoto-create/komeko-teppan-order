package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.StockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 食材・在庫の画面を、実際に<b>描画まで</b>確認するテスト。
 *
 * <p>Thymeleaf の式はコンパイルされません。record のアクセサに {@code ()} を
 * 付け忘れても、プロパティ名を打ち間違えても、ビルドは通りサービスのテストも素通りします。
 * <b>その画面を一度でも本当に描かせる</b>まで誤りは見つかりません。
 *
 * <p>この画面は {@code StockLevel}（record）を大量に読むので、
 * とくにアクセサの書き方を間違えやすいところです。
 *
 * <p>{@code @Transactional} を付けないのは、本番が {@code open-in-view: false} で
 * 画面描画時に DB 接続が無いためです。付けるとその状況を再現できず、
 * 「テストは通るのに本番で LazyInitializationException」を見逃します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材・在庫の画面")
class InventoryIngredientPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private StockService stockService;

    @Test
    @DisplayName("ログインしていなければ入れない")
    void requires_login() throws Exception {
        mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("食材が 1 つも無くても描ける（最初の 1 件への導線が出る）")
    void renders_when_empty() throws Exception {
        // 食材ゼロの状態でも「壊れた画面」ではなく「これから始める画面」を出す。
        // 空の表だけ出して放り出すと、次に何をすればいいか分からない。
        mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/ingredients"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("在庫の表が描ける（record のアクセサが全部通る）")
    void renders_stock_table() throws Exception {
        Ingredient ingredient = ingredients.save(
                new Ingredient("画面テスト用ねぎ-" + System.nanoTime(), IngredientUnit.GRAM));
        stockService.recordStocktake(ingredient.getId(), LocalDate.now().minusDays(1),
                new BigDecimal("1500"), null, null);

        mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ingredient.getName())))
                .andExpect(content().string(containsString("1500")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("詳細画面が描ける（内訳と履歴を含む）")
    void renders_detail() throws Exception {
        Ingredient ingredient = ingredients.save(
                new Ingredient("画面テスト用しめじ-" + System.nanoTime(), IngredientUnit.PACK));
        stockService.recordStocktake(ingredient.getId(), LocalDate.now().minusDays(2),
                new BigDecimal("10"), "初回", null);

        mockMvc.perform(get("/inventory/ingredients/" + ingredient.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/ingredient-detail"))
                .andExpect(content().string(containsString("この数字の内訳")))
                .andExpect(content().string(containsString("記録の履歴")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("登録フォームが描ける")
    void renders_new_form() throws Exception {
        mockMvc.perform(get("/inventory/ingredients/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/ingredient-form"));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("食材を登録できる")
    void can_create() throws Exception {
        String name = "画面テスト用みょうが-" + System.nanoTime();
        mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", name)
                        .param("unit", "GRAM")
                        .param("sortOrder", "0"))
                .andExpect(status().is3xxRedirection());

        assertThat(ingredients.findByName(name)).isPresent();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("同じ名前の食材は登録できない")
    void rejects_duplicate_name() throws Exception {
        String name = "画面テスト用だぶり-" + System.nanoTime();
        ingredients.save(new Ingredient(name, IngredientUnit.GRAM));

        mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", name)
                        .param("unit", "GRAM")
                        .param("sortOrder", "0"))
                .andExpect(status().isOk())   // リダイレクトせず描き直す
                .andExpect(view().name("inventory/ingredient-form"))
                .andExpect(content().string(containsString("同じ名前の食材がすでにあります")));
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("廃棄は正の数で入力しても在庫が減る")
    void waste_is_entered_as_positive_but_reduces_stock() throws Exception {
        // マイナスを人に入力させると、付け忘れて逆に増える事故が必ず起きる。
        // 画面は正の数だけ受け取り、符号はサーバで付ける。
        Ingredient ingredient = ingredients.save(
                new Ingredient("画面テスト用大根-" + System.nanoTime(), IngredientUnit.GRAM));
        stockService.recordStocktake(ingredient.getId(), LocalDate.now().minusDays(1),
                new BigDecimal("1000"), null, null);

        mockMvc.perform(post("/inventory/ingredients/adjust").with(csrf())
                        .param("ingredientId", String.valueOf(ingredient.getId()))
                        .param("takenOn", LocalDate.now().toString())
                        .param("quantity", "300")
                        .param("reason", "WASTE"))
                .andExpect(status().is3xxRedirection());

        assertThat(stockService.levelOf(ingredient.getId()).quantity())
                .isEqualByComparingTo("700");
    }
}
