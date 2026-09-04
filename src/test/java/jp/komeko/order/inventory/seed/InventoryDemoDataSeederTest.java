package jp.komeko.order.inventory.seed;

import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.RecipeCost;
import jp.komeko.order.inventory.service.RecipeService;
import jp.komeko.order.inventory.service.StockLevel;
import jp.komeko.order.inventory.service.StockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 打ち合わせ用のサンプルデータが、<b>本当に説明できる画面になるか</b>を確かめる。
 *
 * <p><b>なぜテストにするのか</b><br>
 * 打ち合わせの直前に画面を開いて空だった、というのがいちばん困ります。
 * 目で見て確かめるやり方だと、確かめた時点でしか安心できません。
 * <b>機械が毎回確かめてくれる</b>ほうが、人前に出す道具としては安心です。
 *
 * <p>ここで見ているのは「動くか」ではなく「<b>話せる状態か</b>」です。
 * 残量が出ているか、原価率に数字が入っているか、
 * 説明したい注意書き（未学習・レシピ未登録）がちゃんと出ているか。
 *
 * <p><b>専用のデータベースで動かす理由</b><br>
 * サンプルデータの投入は「食材が 1 つでもあれば何もしない」という安全装置つきです。
 * ほかのテストが作った食材が残っていると、その安全装置が働いて
 * 何も入らないまま通ってしまいます。
 * {@code properties} を変えると Spring は<b>別の入れ物</b>を用意するので、
 * まっさらな状態から始められます。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventory-demo-seed;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.demo-data=true",
        // 過去 1 年ぶんの架空の帳簿（SalesHistoryDemoSeeder）は、このテストには要らない。
        // 既定の 13 か月ぶんを書くと起動だけで 1 分かかり、
        // 食材とレシピしか見ていないこのテストが一様に遅くなる
        "app.demo-history-months=1",
        "app.seed-on-startup=true",
        "app.inventory.enabled=true",
        "app.backup.enabled=false",
        "spring.devtools.restart.enabled=false",
        "server.tomcat.accesslog.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DisplayName("打ち合わせ用のサンプルデータ")
class InventoryDemoDataSeederTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private StockService stockService;

    @Autowired
    private RecipeService recipeService;

    @Test
    @DisplayName("食材とレシピが入っている")
    void seeds_pantry_and_recipes() {
        assertThat(ingredients.findAll()).hasSize(12);

        long withRecipe = recipeService.costTable().stream()
                .filter(cost -> !cost.isEmpty())
                .count();
        assertThat(withRecipe).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("★ 在庫が「話せる状態」になっている")
    void stock_is_presentable() {
        List<StockLevel> levels = stockService.currentLevels();
        assertThat(levels).hasSize(12);

        // 残量が全部 0 だと「動いていない」ようにしか見えない
        assertThat(levels).anyMatch(l -> l.quantity().signum() > 0);

        // 「あと◯営業日」が 1 つも出ないと、いちばんの売りが説明できない
        assertThat(levels).anyMatch(l -> l.daysLeft() != null);

        // 単価が出ていないと原価の話につながらない
        assertThat(levels).anyMatch(l -> l.unitCostIncludingTax() != null);

        // 注意が要る食材が 1 つも無いと、赤いバッジの説明ができない
        assertThat(levels).anyMatch(StockLevel::needsAttention);
    }

    @Test
    @DisplayName("★ 原価率に数字が入っている（前職のエクセルの再現）")
    void cost_table_has_numbers() {
        List<RecipeCost> costs = recipeService.costTable();

        assertThat(costs).anyMatch(c -> c.costRateIncludingTax() != null
                && c.costRateIncludingTax().signum() > 0);
        assertThat(costs).anyMatch(c -> c.costRateNet() != null
                && c.costRateNet().signum() > 0);

        // 未登録が残っていないと「登録した分から効く」という説明ができない
        assertThat(recipeService.menuItemsWithoutRecipe()).isNotEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("在庫の画面が数字入りで描ける")
    void ingredients_page_shows_data() throws Exception {
        mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("キャベツ")))
                .andExpect(content().string(containsString("営業日")))
                // レシピ未登録の警告。予測が甘くなる理由を説明するための文言
                .andExpect(content().string(containsString("レシピが登録されていません")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("仕入れの画面に月の集計と実際原価率が出る")
    void purchases_page_shows_summary() throws Exception {
        mockMvc.perform(get("/inventory/purchases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("業務スーパー")))
                .andExpect(content().string(containsString("実際原価率")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("原価表に商品と原価率が並ぶ")
    void recipe_page_shows_cost_table() throws Exception {
        mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("肉玉米粉そば")))
                .andExpect(content().string(containsString("%")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("税率マスタに初期データが並ぶ")
    void tax_rate_page_shows_master() throws Exception {
        mockMvc.perform(get("/inventory/tax-rates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("軽減_飲食料品")));
    }
}
