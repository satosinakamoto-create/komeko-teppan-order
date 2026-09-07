package jp.komeko.order.inventory.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.domain.ItemAlias;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.IngredientService;
import jp.komeko.order.inventory.web.form.PurchaseForm;
import jp.komeko.order.inventory.web.form.PurchaseLineForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数量入力の桁あふれ（2026-09-07 の全体点検 #4）。
 *
 * <p><b>何が壊れていたか</b><br>
 * 在庫まわりの数量列はすべて {@code precision=12}（整数 9 桁＋小数 3 桁、
 * 単価だけ整数 8 桁＋小数 4 桁）なのに、フォーム側に上限が無かった。
 * 13 桁を入れると DB の桁あふれ例外がそのまま外へ出て 500。
 * キーを押しっぱなしにしただけで踏める。
 *
 * <p><b>入口は 2 種類ある</b><br>
 * {@code @Valid} なフォームは {@code @Digits} で弾ける。
 * {@code @RequestParam BigDecimal} で受けている 2 箇所
 * （レシピの分量修正・入り数の学習）には Bean Validation が効かないので、
 * 既存の「0 以下チェック」と同じ場所で手前に検査を置く。
 *
 * <p>照合した列とフォーム制約の対応は、この修正のコミットメッセージにある。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("数量入力の桁あふれを 500 にしない")
class QuantityDigitsTest {

    /** 整数 13 桁。precision=12 の列には入らない。 */
    private static final String TOO_BIG = "9999999999999";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredients;
    @Autowired
    private IngredientService ingredientService;
    @Autowired
    private Validator validator;

    private Ingredient ingredient(String marker) {
        return ingredients.save(new Ingredient("桁テスト" + marker + System.nanoTime(), IngredientUnit.GRAM));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 棚卸しに 13 桁 → 500 ではなくフォームのエラーで戻る")
    void stocktakeRejectsOverflow() throws Exception {
        Ingredient i = ingredient("A");

        mockMvc.perform(post("/inventory/ingredients/stocktake").with(csrf())
                        .param("origin", "record")
                        .param("ingredientId", String.valueOf(i.getId()))
                        .param("takenOn", "2026-09-07")
                        .param("quantity", TOO_BIG))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashErrors",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("大きすぎます"))));

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 廃棄（adjust）に 13 桁 → 同じくエラーで戻る")
    void adjustRejectsOverflow() throws Exception {
        Ingredient i = ingredient("B");

        mockMvc.perform(post("/inventory/ingredients/adjust").with(csrf())
                        .param("origin", "record")
                        .param("ingredientId", String.valueOf(i.getId()))
                        .param("takenOn", "2026-09-07")
                        .param("quantity", TOO_BIG)
                        .param("reason", "WASTE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashErrors",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("大きすぎます"))));

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 食材フォーム：警告残量 13 桁・単価の整数 9 桁はエラーで描き直し")
    void ingredientFormRejectsOverflow() throws Exception {
        String html = mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", "桁テスト登録" + System.nanoTime())
                        .param("unit", "GRAM")
                        .param("sortOrder", "0")
                        .param("lowThresholdQty", TOO_BIG)
                        // cost_override は precision=12, scale=4 なので整数は 8 桁まで。
                        // 9 桁ちょうどを入れて、境界が列と合っていることを見る
                        .param("costOverride", "123456789"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("警告残量が大きすぎます");
        assertThat(html).contains("単価が大きすぎます");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ レシピの分量修正（@RequestParam）に 13 桁 → 手前の検査で戻る")
    void recipeUpdateLineRejectsOverflow() throws Exception {
        // 行が実在しなくてもよい。検査はサービスより手前に置くので、
        // 13 桁ならサービスに触る前に戻ることまで含めて確かめている
        mockMvc.perform(post("/inventory/recipes/1/lines/1").with(csrf())
                        .param("qtyPerItem", TOO_BIG))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashErrors",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("大きすぎます"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 入り数の学習（@RequestParam）に 13 桁 → 手前の検査で戻る")
    void learnAliasRejectsOverflow() throws Exception {
        Ingredient i = ingredient("C");
        ItemAlias alias = ingredientService.learn("ｹﾀﾃｽﾄ" + System.nanoTime(), i.getId(), null);

        mockMvc.perform(post("/inventory/ingredients/aliases/" + alias.getId() + "/learn")
                        .with(csrf())
                        .param("qtyPerUnit", TOO_BIG))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashErrors",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("大きすぎます"))));

        // 覚えていない（宙ぶらりんを作らない）こと
        assertThat(ingredientService.unlearnedAliases())
                .extracting(ItemAlias::getId).contains(alias.getId());

        // 後始末は参照している側（紐付け）から。逆順だと FK 違反
        ingredientService.forget(alias.getId());
        ingredients.deleteById(i.getId());
    }

    @Test
    @DisplayName("★ 仕入れ明細の個数・在庫量も @Digits で列に収まる")
    void purchaseLineFormHasDigitsLimits() {
        // 仕入れの登録はウィザード（読取→確認→保存）なので、ここでは
        // フォームの制約そのものを検証する。@Valid の連鎖（PurchaseForm.lines）は
        // 実装側の @Valid 注釈が担う
        PurchaseForm form = new PurchaseForm();
        PurchaseLineForm line = new PurchaseLineForm();
        line.setItemText("桁テスト品");
        line.setAmount(100);
        line.setQuantity(new BigDecimal(TOO_BIG));
        line.setStockQty(new BigDecimal(TOO_BIG));
        form.getLines().add(line);

        Set<ConstraintViolation<PurchaseForm>> violations = validator.validate(form);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("lines[0].quantity", "lines[0].stockQty");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("境界の中は通る（整数 9 桁ちょうどの棚卸し）")
    void nineDigitsStillWorks() throws Exception {
        Ingredient i = ingredient("D");

        mockMvc.perform(post("/inventory/ingredients/stocktake").with(csrf())
                        .param("origin", "record")
                        .param("ingredientId", String.valueOf(i.getId()))
                        .param("takenOn", "2026-09-07")
                        .param("quantity", "999999999.999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        // 後始末：棚卸し行が食材を参照するので食材は消さない（名前が一意なので放置可）
    }
}
