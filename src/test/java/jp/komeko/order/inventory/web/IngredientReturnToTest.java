package jp.komeko.order.inventory.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * レシピ編集の途中で足りない食材に気づいたら、その場で作って戻れる（2026-09-14、店主）。
 *
 * <p><b>何がおかしかったか。</b>レシピ編集の「先に食材を追加してください →」は
 * {@code ingredients.isEmpty()} のときにしか出なかった。
 * 店主の言葉どおり<b>「今ある食材とは関係ない」</b>——リンクの用事は
 * 「これから使う食材が無い」ことであって、他の食材が何個あるかではない。
 * 現実に起きるのは「食材は 30 個あるが、新メニューの大葉だけ無い」で、
 * そのときに限ってリンクが出ない、という逆の条件になっていた。
 *
 * <p><b>もう 1 つの分断：作ったら戻れない。</b>食材を登録すると
 * 食材の詳細ページへ飛ばされ、レシピ編集には戻らない（往復 5 画面）。
 * {@code returnTo} で来た道を覚えて、保存後にレシピ編集へ返す。
 *
 * <p><b>returnTo は内部パスしか信じない。</b>リダイレクト先を
 * リクエストから受け取る作りはオープンリダイレクトの入り口になるので、
 * {@code /inventory/recipes/数字} の形だけ許す。それ以外は既定の遷移。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材の登録から来た道へ戻る（returnTo）")
class IngredientReturnToTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path RECIPE_EDIT =
            Path.of("src/main/resources/templates/inventory/recipe-edit.html");
    private static final Path INGREDIENT_FORM =
            Path.of("src/main/resources/templates/inventory/ingredient-form.html");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** コメントに一致する罠（HallStatMergeTest ほかと同じ）を避ける。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 食材を追加のリンクは食材の有無に関係なく出る")
    void theLinkDoesNotDependOnOtherIngredients() throws Exception {
        String t = withoutComments(read(RECIPE_EDIT));
        // 材料を足す枠（食材がある側の分岐）の中にリンクがあること
        int branch = t.indexOf("th:unless=\"${ingredients.isEmpty()}\"");
        assertThat(branch).as("食材がある側の分岐が無い").isGreaterThan(0);
        int link = t.indexOf("/inventory/ingredients/new", branch);
        assertThat(link).as("食材がある側の分岐にリンクが無い").isGreaterThan(branch);
    }

    @Test
    @DisplayName("★ リンクは returnTo で来た道（このレシピ）を覚えている")
    void theLinkCarriesTheWayBack() throws Exception {
        String t = read(RECIPE_EDIT);
        assertThat(t).contains("returnTo=");
        assertThat(t).contains("/inventory/recipes/' + ${menuItem.id}");
    }

    @Test
    @DisplayName("★ 食材フォームは returnTo を持ち回る（隠し項目）")
    void theFormKeepsTheWayBack() throws Exception {
        String t = read(INGREDIENT_FORM);
        assertThat(t).contains("type=\"hidden\"");
        assertThat(t).contains("returnTo");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ returnTo 付きで登録すると、そのレシピ編集へ戻る")
    void createReturnsToTheRecipe() throws Exception {
        mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", "大葉（戻り道テスト）")
                        .param("unit", "GRAM")
                        .param("returnTo", "/inventory/recipes/5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventory/recipes/5"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 内部パス以外の returnTo は信じない（オープンリダイレクト防止）")
    void suspiciousReturnToIsIgnored() throws Exception {
        String url = mockMvc.perform(post("/inventory/ingredients").with(csrf())
                        .param("name", "大葉（偽の戻り先）")
                        .param("unit", "GRAM")
                        .param("returnTo", "https://evil.example/phish"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(url).startsWith("/inventory/ingredients/");
    }
}
