package jp.komeko.order.inventory.web;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import jp.komeko.order.inventory.service.RecipeService;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「食材を追加」を Figma「ト04b 食材を追加（トンマナRender）」838:9207 に合わせる（2026-09-14）。
 *
 * <p><b>いちばん大きい差は、レシピ名入りの案内が無かったこと。</b>
 * Figma には見出しの下に帯があり、こう書いてあります——
 * <pre>「肉玉米粉そば」のレシピ編集から来ました。登録するとレシピへ戻ります</pre>
 *
 * <p>実装は {@code returnTo}（{@code /inventory/recipes/12} という<b>パスの文字列</b>）しか
 * 持っておらず、<b>どの商品のレシピから来たのかを画面が知りませんでした</b>。
 * だから「レシピ編集から来ています」という、どのレシピか分からない一文しか出せない。
 *
 * <p>レシピを 2 つ並行で直しているときに「どっちに戻るんだ」が分からないのは、
 * 戻り道を作った意味が半分無くなるので、商品名まで出します。
 *
 * <p><b>踏んだ罠（先に書いておく）。</b>商品名を取るのに画面で
 * {@code menuItem.name} と書いてはいけません。{@code open-in-view: false} なので
 * 描画時には DB 接続がなく、{@code LazyInitializationException} で落ちます。
 * {@link RecipeService#categoryNameOf(Long)} と同じく、Service の
 * {@code @Transactional} の中で文字列にしてから渡すこと。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材を追加は Figma ト04b どおり")
class IngredientFormMatchesFigmaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MenuItemRepository menuItems;

    @Autowired
    private InventoryTestFixture fixture;

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/ingredient-form.html");

    private String read() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n");
    }

    private String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 戻り先に使える商品を 1 つ用意する。
     *
     * <p>★ テスト用 DB は空で始まります。{@code findAll().findFirst()} だけで書くと、
     * 「商品が無いから案内も出ない」で<b>通ってしまう</b>当てにならないテストになります
     * （{@code TableQrScreenSplitTest} が卓ゼロで同じ罠を踏みました）。必ず作ってから見ること。
     */
    private MenuItem anyItem() {
        return menuItems.findAll().stream().findFirst()
                .orElseGet(() -> fixture.createMenuItem("肉玉米粉そば", 1180));
    }

    // ---------------------------------------------------------------- 案内帯

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ レシピから来たときは、商品名入りで案内する")
    void theBannerNamesTheRecipeYouCameFrom() throws Exception {
        MenuItem item = anyItem();

        String html = mockMvc.perform(
                        get("/inventory/ingredients/new")
                                .param("returnTo", "/inventory/recipes/" + item.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("案内に商品名が出ていない")
                .contains("「" + item.getName() + "」のレシピ編集から来ました");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ふつうに開いたときは案内を出さない（来た道が無いのに戻り先を語らない）")
    void theBannerStaysAwayWhenYouCameStraightHere() throws Exception {
        String html = mockMvc.perform(get("/inventory/ingredients/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("のレシピ編集から来ました");
    }

    /**
     * 戻り先の検査（{@code safeReturnTo}）を通らない値では、商品名を引きに行かないこと。
     * ここを素通りさせると、存在しない id で 500 になったり、
     * 外部 URL をそのまま画面に出して偽の案内を作れてしまう。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 怪しい戻り先では案内を出さない（オープンリダイレクト対策と同じ関所）")
    void theBannerRefusesJunkReturnPaths() throws Exception {
        for (String junk : new String[]{"https://example.com", "/admin/items", "/inventory/recipes/x"}) {
            String html = mockMvc.perform(get("/inventory/ingredients/new").param("returnTo", junk))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as(junk + " で案内が出ている").doesNotContain("のレシピ編集から来ました");
        }
    }

    // ---------------------------------------------------------------- 形

    @Test
    @DisplayName("★ 入力は 3 列 × 2 行（Figma は 名前/単位/分類 → 警告残量/単価/メモ）")
    void theFieldsSitInTwoRowsOfThree() throws Exception {
        String m = withoutComments(read());

        // 2 列 × 4 行だと、分類の隣に空のセルを置く羽目になっていた
        assertThat(m).as("空のセルで桁を埋めている").doesNotContain("<div class=\"field\"></div>");
        assertThat(m).as("3 列になっていない").contains("grid grid--3");

        // メモは 3 列目に入る。全幅の 1 行を占めない
        int grid = m.lastIndexOf("grid grid--3");
        assertThat(m.substring(grid)).as("メモが 3 列目に無い").contains("for=\"memo\"");
    }

    @Test
    @DisplayName("★ ボタンの脇に「単価はレシートで入る」と書く（Figma の補足）")
    void theHintNextToTheButtonTalksAboutCost() throws Exception {
        String m = withoutComments(read());
        assertThat(m).contains("登録した直後は単価が分かりません");
        assertThat(m).contains("レシートを 1 枚登録すると原価に入ります");
    }
}
