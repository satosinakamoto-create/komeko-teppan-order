package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 在庫モジュールを切った状態でも、商品の削除が動くことを守る。
 *
 * <p>レシピの後始末（2026-09-07 の全体点検 #1）で商品削除が
 * 在庫側の {@code RecipeLineRepository} を使うようになった。
 * その受け取りを {@code ObjectProvider} にしてあるのは、
 * 在庫モジュールの都合で Bean が条件付きにされても、
 * 本体機能である商品削除まで巻き添えにしないため。
 * このテストは {@code app.inventory.enabled=false} の文脈を実際に立てて、
 * その約束が口だけでないことを確かめる。
 */
@SpringBootTest(properties = "app.inventory.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("在庫モジュール無効時の商品削除")
class AdminMenuItemDeleteWithoutInventoryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 在庫モジュールが無効でも商品を削除できる")
    void deleteWorksWithoutTheInventoryModule() throws Exception {
        Category category = categoryRepository.save(
                new Category("在庫無効テスト" + System.nanoTime(), 999));
        MenuItem item = menuItemRepository.save(
                new MenuItem(category, "在庫無効テスト品" + System.nanoTime(), 500));

        mockMvc.perform(post("/admin/items/" + item.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(menuItemRepository.findById(item.getId())).isEmpty();
    }
}
