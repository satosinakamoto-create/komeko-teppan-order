package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 食材・在庫の作り直し（設計 現04 441:2715）と、
 * 棚卸し・廃棄の記録ページ（設計 現05 443:2940）。2026-09-07。
 *
 * <p><b>何を変えたか</b><br>
 * 一覧のいちばん下にあった 2 つの記録フォームを専用ページへ移し、
 * 一覧には検索を足しました。一覧は「いま何がどれだけあるか」を読む画面、
 * 記録は仕込みの前後にまとめてやる別の仕事、という切り分けです。
 *
 * <p><b>いちばん守っているのは記録の往復</b>です。
 * 仕込み後は何品も続けて記録するので、1 件記録するたびに一覧へ
 * 飛ばされたり、選んでいた食材が外れたりすると作業になりません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材・在庫と記録ページ（現04 / 現05）")
class StockRecordPageTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredients;

    private static final Path LIST =
            Path.of("src/main/resources/templates/inventory/ingredients.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧に記録フォームが無く、記録ページへの口がある")
    void listHasNoFormsButLinks() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("記録導線テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        String html = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

        // 記録フォームは専用ページへ移した。両方に残すと、直した片方だけが
        // 生きているように見える（同じフォームが 2 か所で違う動きをする）
        assertThat(main).as("一覧に棚卸しフォームが残っている")
                .doesNotContain("/inventory/ingredients/stocktake");
        assertThat(main).as("記録ページへの口が無い")
                .contains("/inventory/ingredients/record?ingredient=" + i.getId());

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 記録ページは、指定した食材が選ばれた状態で開く")
    void recordPagePreselectsTheIngredient() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("選択テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        String html = mockMvc.perform(get("/inventory/ingredients/record")
                        .param("ingredient", String.valueOf(i.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("棚卸し（数え直す）");
        assertThat(html).contains("廃棄・まかない（減らす）");
        // th:field は一致した option に selected を付ける。
        // 一覧の「記録する」から来た人に食材を選び直させないための要
        assertThat(html).as("食材が選ばれていない")
                .contains("value=\"" + i.getId() + "\" selected");

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 記録したあとは記録ページに留まる（食材の選択も保つ）")
    void recordingStaysOnTheRecordPage() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("往復テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        mockMvc.perform(post("/inventory/ingredients/adjust")
                        .param("origin", "record")
                        .param("ingredientId", String.valueOf(i.getId()))
                        .param("takenOn", "2026-09-07")
                        .param("quantity", "50")
                        .param("reason", "WASTE")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 一覧へ飛ばすと、続けて記録する人が毎回開き直すことになる
                .andExpect(redirectedUrl("/inventory/ingredients/record?ingredient=" + i.getId()));

        // ★ この食材は消さない。いま記録した棚卸しの行が参照しているので、
        //   消すと外部キー違反で落ちる（実際に落とした）。
        //   名前に nanoTime が入っているため、残しても他のテストとは衝突しない
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 名前で探せる（部分一致・他の食材は出ない）")
    void searchFiltersByName() throws Exception {
        long n = System.nanoTime();
        Ingredient hit = ingredients.save(new Ingredient("検索ヒット米粉-" + n, IngredientUnit.GRAM));
        Ingredient miss = ingredients.save(new Ingredient("検索ハズレ卵-" + n, IngredientUnit.PIECE));

        String html = mockMvc.perform(get("/inventory/ingredients").param("q", "検索ヒット"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("検索ヒット米粉-" + n);
        assertThat(html).as("絞り込めていない").doesNotContain("検索ハズレ卵-" + n);

        ingredients.deleteById(hit.getId());
        ingredients.deleteById(miss.getId());
    }

    @Test
    @DisplayName("★ 件数の知らせは出さない（バッジが同じ情報を持っている）")
    void attentionAlertIsGone() throws Exception {
        // 設計 現04 で削除された。知らせは行の「少ない」バッジの件数を
        // 言い直していただけで、消しても情報は失われない
        String html = Files.readString(LIST).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html).doesNotContain("の食材が少なくなっているか");
        // バッジのほうは残っていること（こちらを消すと本当に情報が消える）
        assertThat(html).contains(">少ない</span>");
        assertThat(html).contains(">マイナス</span>");
    }

    @Test
    @DisplayName("★ 画面の寸法（py32・検索48・行の上下16）が設計値のまま")
    void designMetrics() throws Exception {
        String css = Files.readString(CSS);

        // この画面だけ縦の余白 32（設計で 64→32 に編集された）。
        // 共通の --main-pad-y は触らず :has で絞る
        assertThat(css).contains(".theme-desk .staff-main:has(.inv-ingredients) { --main-pad-y: 32px; }");
        assertThat(Files.readString(LIST)).contains("section-title inv-ingredients");

        // 検索は 48。設計は 40 だが、タップ 48px の下限（CLAUDE.md）で止めた
        assertThat(css).contains(".searchbox--slim { height: 48px; }");

        // 行の上下 16（設計で 14→16 に編集された）
        assertThat(css).contains(".theme-desk .table--stock td { padding-top: 16px; padding-bottom: 16px; }");
    }

    @Test
    @DisplayName("厨房ボードの見出し（現01）：上下 20・題 28・説明 13")
    void kitchenHeadingMetrics() throws Exception {
        // 実測との差はこの 3 つだけだった（他は全て一致）。
        // .griddle は厨房ボードにしか付いていないので、他画面は動かない
        String css = Files.readString(CSS);
        assertThat(css).contains(".griddle .card__body { padding: 20px 24px; }");
        assertThat(css).contains(".griddle h1 { font-size: 28px; }");
        assertThat(css).contains(".griddle .small { font-size: 13px; }");
    }
}
