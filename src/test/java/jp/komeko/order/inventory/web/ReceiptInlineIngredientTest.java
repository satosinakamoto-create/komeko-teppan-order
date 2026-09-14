package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.service.IngredientService;
import jp.komeko.order.inventory.web.form.PurchaseLineForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * レシートの読み取り確認から、その場で食材を作る（2026-09-14、店主）。
 *
 * <p><b>何が問題だったか。</b>明細の「食材との紐付け」は既存の食材しか選べず、
 * 新しい食材を仕入れた回は<b>この画面が手詰まり</b>だった。
 * 食材・在庫へ行って登録し、レシートを最初から入れ直すしかない。
 * レシピ編集の「食材が無い」と同根の分断（{@code IngredientReturnToTest}）。
 *
 * <p><b>作り。</b>行の「食材登録」ボタンはフォーム全体を送る
 * （{@code name="createIngredientRow" value=行番号}）。サーバは品名から
 * 食材名を作り、行の単位で登録して、その行に紐付けたまま確認画面を描き直す。
 * <b>入力した他の行は一切消えない</b>（全部 POST で往復しているから）。
 * 入力エラーで描き直す既存の道（{@code create} の
 * {@code bindingResult.hasErrors()}）とまったく同じ形。
 *
 * <p><b>決めたこと（2026-09-14、店主と）</b>
 * <ul>
 *   <li>税額の列は残す（印字された値をそのまま残す：電帳法）。モックにも追加した</li>
 *   <li>「次から自動」チェックは常時 ON にして隠す（hidden）。
 *       解除したければ食材の設定から</li>
 *   <li>行の操作は「食材登録（必要な行だけ）」と「✕」の 2 つ。
 *       Render 式の「…」は中身を説明できないのでやめた</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("レシートの確認画面から、その場で食材を作る")
class ReceiptInlineIngredientTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngredientService ingredientService;

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/purchase-form.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n");
    }

    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- 品名の掃除

    @Nested
    @DisplayName("品名から食材名を作る（包装の表記を落とす）")
    class SuggestedName {

        private String clean(String itemText) {
            PurchaseLineForm f = new PurchaseLineForm();
            f.setItemText(itemText);
            return f.suggestedIngredientName();
        }

        @Test
        @DisplayName("★ 「エリンギ 2P」→「エリンギ」")
        void dropsPackCount() {
            assertThat(clean("エリンギ 2P")).isEqualTo("エリンギ");
        }

        @Test
        @DisplayName("★ 「米粉 10kg」→「米粉」")
        void dropsWeight() {
            assertThat(clean("米粉 10kg")).isEqualTo("米粉");
        }

        @Test
        @DisplayName("★ 「キャベツ」はそのまま")
        void plainNameSurvives() {
            assertThat(clean("キャベツ")).isEqualTo("キャベツ");
        }

        @Test
        @DisplayName("★ 前後の空白は落とす")
        void trims() {
            assertThat(clean("  大葉 3枚 ")).isEqualTo("大葉");
        }
    }

    // ---------------------------------------------------------------- その場で作る

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 食材登録を押すと、食材ができて行に紐付き、他の入力は消えない")
    void createButtonMakesAndLinksTheIngredient() throws Exception {
        MvcResult result = mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("createIngredientRow", "0")
                        .param("purchasedOn", "2026-09-13")
                        .param("storeName", "業務スーパー（その場で作るテスト）")
                        .param("totalAmount", "256")
                        .param("lines[0].itemText", "エリンギテスト 2P")
                        .param("lines[0].amount", "256")
                        .param("lines[0].taxRatePercent", "8")
                        .param("lines[0].newUnit", "GRAM")
                        .param("lines[1].itemText", "他の行（消えないこと）"))
                .andExpect(status().isOk())
                .andExpect(view().name("inventory/purchase-form"))
                .andReturn();

        // 食材ができている（名前は包装表記を落としたもの）
        var made = ingredientService.activeIngredients().stream()
                .filter(i -> i.getName().equals("エリンギテスト"))
                .findFirst();
        assertThat(made).as("食材が作られていない").isPresent();
        assertThat(made.get().getUnit()).isEqualTo(IngredientUnit.GRAM);

        // 行に紐付いたまま、他の行も残って描き直されている
        Map<String, Object> model = result.getModelAndView().getModel();
        var form = (jp.komeko.order.inventory.web.form.PurchaseForm) model.get("purchaseForm");
        assertThat(form.getLines().get(0).getIngredientId()).isEqualTo(made.get().getId());
        assertThat(form.getLines().get(1).getItemText()).isEqualTo("他の行（消えないこと）");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 同じ名前が既にあれば、作らずにそれへ紐付ける（分裂させない）")
    void duplicateNameLinksTheExistingOne() throws Exception {
        var existing = ingredientService.create("大葉テスト", IngredientUnit.PIECE,
                null, null, null, null);

        MvcResult result = mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("createIngredientRow", "0")
                        .param("lines[0].itemText", "大葉テスト 3個")
                        .param("lines[0].newUnit", "GRAM"))
                .andExpect(status().isOk())
                .andReturn();

        long count = ingredientService.activeIngredients().stream()
                .filter(i -> i.getName().equals("大葉テスト")).count();
        assertThat(count).as("同名の食材が分裂した").isEqualTo(1);

        var form = (jp.komeko.order.inventory.web.form.PurchaseForm)
                result.getModelAndView().getModel().get("purchaseForm");
        assertThat(form.getLines().get(0).getIngredientId()).isEqualTo(existing.getId());
    }

    // ---------------------------------------------------------------- 行の増減

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ✕ で行が消える（他の行はそのまま）")
    void removeRowDropsOnlyThatLine() throws Exception {
        MvcResult result = mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("removeRow", "0")
                        .param("lines[0].itemText", "消える行")
                        .param("lines[1].itemText", "残る行"))
                .andExpect(status().isOk())
                .andReturn();
        var form = (jp.komeko.order.inventory.web.form.PurchaseForm)
                result.getModelAndView().getModel().get("purchaseForm");
        assertThat(form.getLines().get(0).getItemText()).isEqualTo("残る行");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ＋行を足すで空の行が増える")
    void addLineAppendsAnEmptyRow() throws Exception {
        MvcResult result = mockMvc.perform(post("/inventory/purchases").with(csrf())
                        .param("addLine", "1")
                        .param("lines[0].itemText", "既存の行"))
                .andExpect(status().isOk())
                .andReturn();
        var form = (jp.komeko.order.inventory.web.form.PurchaseForm)
                result.getModelAndView().getModel().get("purchaseForm");
        assertThat(form.getLines().size()).isGreaterThanOrEqualTo(2);
        assertThat(form.getLines().get(0).getItemText()).isEqualTo("既存の行");
    }

    // ---------------------------------------------------------------- 画面の形

    @Test
    @DisplayName("★ 確認画面はモックどおり（帯・操作列・行を足す）")
    void theTemplateMatchesTheMock() throws Exception {
        String t = withoutComments(tpl());
        // 見出しの帯（確認のときだけ）
        assertThat(t).contains("レシートの読み取り確認");
        // 行の操作：食材登録と ✕
        assertThat(t).contains("name=\"createIngredientRow\"");
        assertThat(t).contains("name=\"removeRow\"");
        assertThat(t).contains("name=\"addLine\"");
        // 単位の選択（未紐付けの食材の行だけ）
        assertThat(t).contains("newUnit");
        // 税額の列は残す（2026-09-14 に店主と決めた）
        assertThat(t).contains("税額");
    }

    @Test
    @DisplayName("★ 「次から自動」は常時 ON の隠し項目（チェックは見せない）")
    void aliasLearningIsAlwaysOnAndHidden() throws Exception {
        String t = withoutComments(tpl());
        assertThat(t).doesNotContain("次から自動");
        assertThat(t).contains("type=\"hidden\" th:field=\"*{lines[__${iter.index}__].learnAlias}\"");
    }

    @Test
    @DisplayName("★ 要対応の行は薄い赤（食材の行なのに紐付いていない）")
    void theUnlinkedFoodRowIsMarked() throws Exception {
        String t = withoutComments(tpl());
        assertThat(t).contains("is-unlinked");
        String css = Files.readString(CSS).replace("\r\n", "\n");
        assertThat(css).contains(".receiptpage tr.is-unlinked");
        assertThat(css).contains(".receiptpage tr.is-missing-qty");
    }
}
