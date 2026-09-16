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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * レシピの取り込み（設計 ト09b 806:8314・2026-09-16）。
 *
 * <p><b>この画面は AI 抜きで成立します。</b>空の状態で開いて手で入力しても
 * まったく同じように動き、AI は後からこの入れ物に値を詰めるだけです。
 * ここで固定しているのは<b>その手入力の経路</b>で、
 * {@code ANTHROPIC_API_KEY} が無くても全部通ります。
 *
 * <p><b>レシート確認画面と同型です。</b>1 つの {@code <form>} が全カード・全行を包み、
 * 「＋材料を足す」「✕」「＋その場で作成」はどれもフォーム全部を POST して描き直す。
 * だから<b>入力済みの他の行が消えない</b>——これが JavaScript を使わない理由で、
 * このテストがいちばん守りたいところです（{@link #addingARowKeepsWhatWasTyped()}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("レシピの取り込み")
class RecipeImportPageTest {

    private static final String START = "/inventory/recipes/import";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private IngredientRepository ingredients;

    @Autowired
    private InventoryTestFixture fixture;

    private MenuItem takoyaki;
    private Ingredient cabbage;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        takoyaki = fixture.createMenuItem("取り込みテスト用たこ焼き-" + suffix, 400);
        cabbage = ingredients.save(
                new Ingredient("取り込みテスト用キャベツ-" + suffix, IngredientUnit.GRAM));
    }

    /** 確認画面を 1 品ぶん埋めた POST。コマンドごとに使い回す。 */
    private MockHttpServletRequestBuilder confirmPost() {
        return post(START).with(csrf())
                .param("items[0].readName", takoyaki.getName())
                .param("items[0].menuItemId", String.valueOf(takoyaki.getId()))
                .param("items[0].lines[0].readName", "キャベツ")
                .param("items[0].lines[0].ingredientId", String.valueOf(cabbage.getId()))
                .param("items[0].lines[0].qtyPerItem", "150");
    }

    // ---------------------------------------------------------------- 入口

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 原価表から「レシピを取り込む」で入れる")
    void theListHasTheImportButton() throws Exception {
        mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("レシピを取り込む")))
                .andExpect(content().string(containsString(START)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 取り込みの入口が開く")
    void theStartScreenOpens() throws Exception {
        mockMvc.perform(get(START))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("レシピの取り込み")));
    }

    /**
     * ★ AI が使えなくても手で入力して先へ進めること。
     *
     * <p>キーが無い環境でも取り込み画面が使える、というのがこの設計の前提です。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 写真なし（手入力）で確認画面を開ける")
    void theConfirmScreenOpensWithoutAnyPhoto() throws Exception {
        mockMvc.perform(post(START + "/manual").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("読み取った材料")))
                .andExpect(content().string(containsString("食材との照合")))
                .andExpect(content().string(containsString("1品あたりの量")));
    }

    // ---------------------------------------------------------------- 画面の操作

    /**
     * ★ このテストがこの設計のいちばんの要です。
     *
     * <p>「＋ 材料を足す」を押したときに、すでに入力した他の行が消えないこと。
     * JavaScript を使わずフォーム全部を往復させているのは、これを保証するためです。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 材料を足しても、入力済みの行が消えない")
    void addingARowKeepsWhatWasTyped() throws Exception {
        String html = mockMvc.perform(confirmPost().param("addLine", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("入力した材料名が消えた").contains("キャベツ");
        assertThat(html).as("入力した分量が消えた").contains("150");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ✕ で材料の行を消せる")
    void removingARowWorks() throws Exception {
        String marker = "消える行-" + System.nanoTime();

        String html = mockMvc.perform(confirmPost()
                        .param("items[0].lines[1].readName", marker)
                        .param("removeRow", "0:1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("消したはずの行が残っている").doesNotContain(marker);
        assertThat(html).as("消していない行まで消えた").contains("150");
    }

    /**
     * ★ 判定は「ドロップダウンに出てこない文字列」で行うこと。
     *
     * <p>食材名や商品名で判定すると、カードが消えても選択肢の {@code <option>} に
     * 同じ文字が並んでいるため、消えたかどうかを見分けられません
     * （最初にこれで誤検知しました）。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ✕ この品を外す でカードごと消せる")
    void removingAnItemCardWorks() throws Exception {
        String marker = "消えるはずの材料-" + System.nanoTime();

        mockMvc.perform(post(START).with(csrf())
                        .param("items[0].menuItemId", String.valueOf(takoyaki.getId()))
                        .param("items[0].lines[0].readName", marker)
                        .param("items[0].lines[0].qtyPerItem", "150")
                        .param("removeItem", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(marker))));
    }

    /**
     * 知らない食材が出てきたら、別画面へ行かずにその場で作れること。
     * レシート確認画面の「食材登録」とまったく同じ仕組みです。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ＋ その場で作成 で食材を作って、その行に紐付く")
    void creatingAnIngredientInlineLinksTheRow() throws Exception {
        String newName = "取り込みで作った大葉-" + System.nanoTime();

        String html = mockMvc.perform(post(START).with(csrf())
                        .param("items[0].menuItemId", String.valueOf(takoyaki.getId()))
                        .param("items[0].lines[0].readName", newName)
                        .param("items[0].lines[0].qtyPerItem", "3")
                        .param("items[0].lines[0].newUnit", "PIECE")
                        .param("createIngredientRow", "0:0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ingredients.findByName(newName)).as("食材が作られていない").isPresent();
        assertThat(html).as("作った食材が行に紐付いていない")
                .contains(String.valueOf(ingredients.findByName(newName).orElseThrow().getId()));
    }

    // ---------------------------------------------------------------- 保存

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ まとめて登録すると、レシピ行が入る")
    void registeringWritesTheRecipeLines() throws Exception {
        mockMvc.perform(confirmPost().param("register", "1"))
                .andExpect(status().is3xxRedirection());

        assertThat(recipeService.linesOf(takoyaki.getId()))
                .as("レシピ行が入っていない")
                .hasSize(1);
        assertThat(recipeService.linesOf(takoyaki.getId()).get(0).getQtyPerItem())
                .isEqualByComparingTo("150");
    }

    /**
     * ★ 「やめる（何も登録しない）」は本当に何も残さないこと。
     *
     * <p>ここが漏れると、確認画面を開いただけで中途半端なレシピが入ります。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ やめる を押すと 1 行も登録されない")
    void cancellingWritesNothing() throws Exception {
        mockMvc.perform(confirmPost().param("cancel", "1"))
                .andExpect(status().is3xxRedirection());

        assertThat(recipeService.linesOf(takoyaki.getId()))
                .as("やめたのに登録されている").isEmpty();
    }

    /**
     * 商品が決まっていないカードは登録しない。
     * Figma の赤いバッジ「商品が見つかりません」がそのまま残る状態です。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品が照合できていないカードは登録されない")
    void anUnmatchedItemIsNotRegistered() throws Exception {
        mockMvc.perform(post(START).with(csrf())
                        .param("items[0].readName", "存在しない商品")
                        .param("items[0].lines[0].readName", "キャベツ")
                        .param("items[0].lines[0].ingredientId", String.valueOf(cabbage.getId()))
                        .param("items[0].lines[0].qtyPerItem", "150")
                        .param("register", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("商品が見つかりません")));
    }

    /**
     * ★ 在庫を狂わせないための検証。
     *
     * <p>分量が空の行を通すと、その材料は原価にも消費にも入りません。
     * 「登録したのに在庫が減らない」という形で静かに跳ね返るので、ここで止めます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 分量が空の行は登録できない（静かに在庫が狂うため）")
    void aLineWithoutQuantityIsRejected() throws Exception {
        mockMvc.perform(post(START).with(csrf())
                        .param("items[0].menuItemId", String.valueOf(takoyaki.getId()))
                        .param("items[0].lines[0].readName", "キャベツ")
                        .param("items[0].lines[0].ingredientId", String.valueOf(cabbage.getId()))
                        .param("register", "1"))
                .andExpect(status().isOk());

        assertThat(recipeService.linesOf(takoyaki.getId()))
                .as("分量が空なのに登録された").isEmpty();
    }
}
