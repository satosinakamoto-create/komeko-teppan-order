package jp.komeko.order.web.kitchen;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * 品切れ・残数を「カテゴリ 1 つずつ」にする（2026-09-07 / 設計 現03 439:2496）。
 *
 * <p><b>なぜ変えたか</b><br>
 * それまでは 14 カテゴリ 94 品を 1 ページに縦に並べ、上に札を 14 個折り返して
 * 置いていました。札は見出しへ飛ぶ目印で、押しても<b>ページの中を移動するだけ</b>。
 * 目的の行に着くまで、残り 13 カテゴリぶんを通り過ぎていました。
 *
 * <p>この画面を開く理由は「いまこの品を止めたい」「今日の数を入れたい」で、
 * 対象はたいてい 1 つのカテゴリの中にあります。
 *
 * <p><b>探すときだけカテゴリを無視します。</b>
 * 「たこ焼」と打つ人は、それがどのカテゴリにあるかを覚えていません
 * （覚えていたらカテゴリから辿ります）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("品切れ・残数のカテゴリ選択")
class StockCategoryPickTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Category first;
    private Category second;
    private MenuItem onlyInSecond;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime() % 100000;
        first = categoryRepository.save(new Category("A粉もの" + n, 1));
        second = categoryRepository.save(new Category("Bドリンク" + n, 2));
        menuItemRepository.save(new MenuItem(first, "テスト粉もの" + n, 1000));
        onlyInSecond = menuItemRepository.save(new MenuItem(second, "テストたこ焼" + n, 600));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 選んだカテゴリの品だけが出る（他のカテゴリは出ない）")
    void showsOnlyTheChosenCategory() throws Exception {
        String html = mockMvc.perform(get("/kitchen/stock").param("category", String.valueOf(second.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));
        // 表に出るのは選んだカテゴリの品だけ。
        // 他のカテゴリ名は「選択肢」としては出るので、行の目印（item-◯）で見る
        assertThat(main).as("選んだ品が出ていない").contains("item-" + onlyInSecond.getId());

        MenuItem other = menuItemRepository.findAll().stream()
                .filter(i -> i.getCategory() != null && i.getCategory().getId().equals(first.getId()))
                .findFirst().orElseThrow();
        assertThat(main).as("選んでいないカテゴリの品まで出ている")
                .doesNotContain("item-" + other.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 未指定なら先頭のカテゴリ（空の画面から始めない）")
    void defaultsToTheFirstCategory() throws Exception {
        String html = mockMvc.perform(get("/kitchen/stock"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 空の画面にすると、何を押せばよいのか分からない
        assertThat(html.substring(html.indexOf("<main"))).contains("item-");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 探すときはカテゴリをまたぐ")
    void searchIgnoresTheCategory() throws Exception {
        // 「たこ焼」は 2 つめのカテゴリにある。カテゴリを指定していなくても出ること
        String html = mockMvc.perform(get("/kitchen/stock").param("q", "テストたこ焼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html.substring(html.indexOf("<main")))
                .as("検索がカテゴリに縛られている").contains("item-" + onlyInSecond.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 操作したあとも同じカテゴリに残る")
    void staysInTheSameCategoryAfterAnAction() throws Exception {
        // 「鉄板おつまみ」を 3 品続けて品切れにしたい人が、
        // 1 品押すたびに先頭のカテゴリへ飛ばされると作業にならない
        mockMvc.perform(post("/kitchen/stock/{id}/toggle", onlyInSecond.getId())
                        .param("category", String.valueOf(second.getId()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/kitchen/stock?category=" + second.getId()
                        + "#item-" + onlyInSecond.getId()));
    }

    @Test
    @DisplayName("★ カテゴリの一覧は JavaScript 無しで開く（details）")
    void categoryListNeedsNoScript() throws Exception {
        // 厨房の端末で JS が動かなくても、選べなくなってはいけない
        String html = Files.readString(
                Path.of("src/main/resources/templates/kitchen/stock.html"));
        assertThat(html).contains("<details class=\"catpick\"");
        assertThat(html).as("選択肢がリンクでない（押せなくなる）").contains("class=\"catpick__item\"");
    }

    @Test
    @DisplayName("使わなくなったカテゴリの札は残していない")
    void oldChipsAreGone() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/templates/kitchen/stock.html")))
                .doesNotContain("stock-chip");
        // CSS も定義ごと外す（残すと、次に触る人が現役だと思う）
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(css).as("使われていない札の CSS が残っている").doesNotContain(".stock-chip");
    }
}
