package jp.komeko.order.web;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.IngredientService;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * サーバが必須にしている入力欄が、画面の HTML に実在することを見る
 * （2026-09-07 の全体点検・観点 14）。
 *
 * <p><b>この事故は一度起きている。</b>2026-08-31 の UI 監査で、
 * 廃棄フォームに日付欄が無く、サーバの「日付を入力してください」に
 * 必ず弾かれて<b>ブラウザから一度も廃棄を記録できない</b>状態が見つかった。
 * サービスのテストは {@code .param("takenOn", ...)} を自分で付けるので素通りし、
 * 誰も気づかなかった。
 *
 * <p>だからここでは<b>実際に描画した HTML</b> に欄があることを直接見る。
 * テンプレートの文字列検査ではなく描画まで通すのは、
 * {@code th:if} の分岐で欄ごと消える壊れ方も捕まえるため。
 *
 * <p>5 本とも、対象の欄を一時的にテンプレートから外して赤になることを
 * 確かめてから残している（2026-09-07）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("必須入力の欄が画面に実在する")
class RequiredFieldPresenceTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private IngredientService ingredientService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private String html(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 開いている伝票の画面を描く（会計まわりのフォームは active のときだけ出る）。 */
    private TableSession openBill() {
        DiningTable table = diningTableRepository.save(
                new DiningTable("欄テスト" + (System.nanoTime() % 100000), 4, 991));
        return tableService.openSession(table.getId(), 2);
    }

    private void closeBill(TableSession bill) {
        tableSessionRepository.deleteById(bill.getId());
        diningTableRepository.deleteById(bill.getDiningTable().getId());
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 1. 会計画面にお支払い方法のラジオが 2 つ（現金／カード）ある")
    void billPageHasPaymentMethodRadios() throws Exception {
        // サーバは paymentMethod 未選択の会計を拒否する（TableService.closeSession）。
        // このラジオが画面から消えると、拒否だけが残って会計が一切締められなくなる。
        // 廃棄の日付欄と同型の、営業が止まる側の事故
        TableSession bill = openBill();
        try {
            String html = html("/hall/bills/" + bill.getId());
            assertThat(html).as("現金のラジオが無い")
                    .contains("name=\"paymentMethod\" value=\"CASH\"");
            assertThat(html).as("カードのラジオが無い")
                    .contains("name=\"paymentMethod\" value=\"CARD\"");
        } finally {
            closeBill(bill);
        }
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 2. 会計画面に人数とチャージ除外の select がある")
    void billPageHasGuestCountSelects() throws Exception {
        // guestCount / chargeExemptCount はサーバ側の必須パラメータ。
        // select が消えると、ボタンを押した瞬間に 400 が出て
        // 人数もチャージ除外も一切変更できなくなる
        TableSession bill = openBill();
        try {
            String html = html("/hall/bills/" + bill.getId());
            assertThat(html).as("人数の select が無い")
                    .contains("name=\"guestCount\"");
            assertThat(html).as("チャージ除外の select が無い")
                    .contains("name=\"chargeExemptCount\"");
        } finally {
            closeBill(bill);
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 3. 未学習のレシート品名フォームに入り数の入力欄がある")
    void unlearnedFormHasQtyPerUnitInput() throws Exception {
        // qtyPerUnit はサーバ側の必須パラメータ（learnAlias）。
        // 入力欄が消えると、レシートの品名を永遠に教えられなくなり、
        // 「教えれば在庫が正しくなる」宿題一覧が片付かないまま溜まり続ける
        Ingredient i = ingredientRepository.save(
                new Ingredient("欄テスト食材" + System.nanoTime(), IngredientUnit.GRAM));
        var alias = ingredientService.learn("ﾗﾝﾃｽﾄ" + System.nanoTime(), i.getId(), null);
        try {
            assertThat(html("/inventory/ingredients"))
                    .as("入り数の入力欄が無い").contains("name=\"qtyPerUnit\"");
        } finally {
            ingredientService.forget(alias.getId());
            ingredientRepository.deleteById(i.getId());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 4. レシピ編集画面に 1 品あたりの量の入力欄がある")
    void recipeEditHasQtyPerItemInput() throws Exception {
        // qtyPerItem は材料追加（RecipeLineForm）の必須項目。
        // 入力欄が消えると新しい材料を 1 つも登録できず、
        // 原価も「あと◯営業日」もそこで止まる
        Category category = categoryRepository.save(
                new Category("欄テスト" + System.nanoTime(), 998));
        MenuItem item = menuItemRepository.save(
                new MenuItem(category, "欄テスト品" + System.nanoTime(), 700));
        // 追加フォームは食材が 1 つも無いと出ない（そのときは
        // 「先に食材を追加してください」の案内が出る、正しい分岐）。
        // 欄が出るべき状態を作ってから、欄があることを見る
        Ingredient ing = ingredientRepository.save(
                new Ingredient("欄テスト材料" + System.nanoTime(), IngredientUnit.GRAM));
        try {
            assertThat(html("/inventory/recipes/" + item.getId()))
                    .as("1品あたりの量の入力欄が無い").contains("name=\"qtyPerItem\"");
        } finally {
            menuItemRepository.deleteById(item.getId());
            categoryRepository.deleteById(category.getId());
            ingredientRepository.deleteById(ing.getId());
        }
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    @DisplayName("★ 5. 仕訳ルール画面に勘定科目と税区分の入力欄がある")
    void journalRulesHaveAccountAndTaxClassInputs() throws Exception {
        // accountName / taxClassName はサーバ側の必須パラメータ（updateRule）。
        // 入力欄が消えると税制改正のたびに仕訳ルールを直せなくなり、
        // 税理士に渡す仕訳が古い科目のまま出続ける。
        // 行は JournalRuleInitializer が起動時に必ず播くので、空にはならない
        String html = html("/accountant/rules");
        assertThat(html).as("勘定科目の入力欄が無い").contains("name=\"accountName\"");
        assertThat(html).as("税区分の入力欄が無い").contains("name=\"taxClassName\"");
    }
}
