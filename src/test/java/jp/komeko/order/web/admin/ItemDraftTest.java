package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 書きかけの商品（編集中）。設計 08-2 商品を追加（315:1983）／08-3（318:2012）。
 *
 * <p><b>いちばん守っているのは「書きかけがお客さまに出ないこと」です。</b><br>
 * 作っている途中の商品がメニューに並ぶと、価格を入れる前の品が
 * ¥0 や「時価」として注文できてしまいます。
 *
 * <p><b>実際に一度出しました（2026-09-07）。</b>
 * {@link MenuItem#isOrderable()} に {@code !draft} を足しただけで安心してしまい、
 * <b>お客さまのメニューの問い合わせが isOrderable を通っていない</b>ことを
 * 見落としていました。あちらは SQL で {@code visible} だけを見ています。
 * 下書きとして保存した「秋の鉄板きのこ」が、そのままメニューに並びました。
 *
 * <p>だからいまは 3 か所で落としています。どれか 1 つでも欠けたら気づけるように、
 * ここで 3 つとも確かめます。
 * <ol>
 *   <li>保存するとき（掲載も同時に落とす）</li>
 *   <li>SQL（{@code findVisibleForCustomer} の where）</li>
 *   <li>{@code isOrderable()}</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("書きかけの商品（編集中）")
class ItemDraftTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Category category() {
        return categoryRepository.save(new Category("下書きテスト" + System.nanoTime(), 999));
    }

    @Nested
    @DisplayName("お客さまに出さない")
    class 出さない {

        @Test
        @DisplayName("★ 書きかけはお客さまのメニューの問い合わせに出てこない")
        void draftIsNotInTheCustomerQuery() {
            Category c = category();
            MenuItem draft = new MenuItem(c, "書きかけの品" + System.nanoTime(), 0);
            draft.setDraft(true);
            // ★ visible を true のままにしておくのがこのテストの肝。
            //   保存側の手当て（visible=false）に頼らず、SQL 単体で落ちることを見る
            draft.setVisible(true);
            menuItemRepository.save(draft);

            assertThat(menuItemRepository.findVisibleForCustomer())
                    .as("書きかけがお客さまのメニューに漏れている")
                    .noneMatch(m -> m.getId().equals(draft.getId()));

            menuItemRepository.deleteById(draft.getId());
            categoryRepository.deleteById(c.getId());
        }

        @Test
        @DisplayName("★ 書きかけは注文できない（isOrderable が false）")
        void draftIsNotOrderable() {
            MenuItem item = new MenuItem(null, "書きかけ", 1000);
            item.setVisible(true);
            item.setSoldOut(false);
            item.setDraft(true);
            assertThat(item.isOrderable()).as("書きかけなのに注文できる状態").isFalse();

            item.setDraft(false);
            assertThat(item.isOrderable()).as("書きかけを外しても注文できない").isTrue();
        }
    }

    @Nested
    @DisplayName("保存する")
    class 保存 {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("★ 価格が空でも下書きとして保存できる")
        void savesDraftWithoutPrice() throws Exception {
            Category c = category();
            String name = "下書き" + System.nanoTime();

            mockMvc.perform(post("/admin/items")
                            .param("action", "draft")
                            .param("categoryId", String.valueOf(c.getId()))
                            .param("name", name)
                            .param("price", "")          // 空のまま
                            .param("cookMinutes", "")
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    // 保存したものが 100 品の中に紛れないよう、編集中のタブへ戻す
                    .andExpect(redirectedUrl("/admin/items?tab=draft"));

            MenuItem saved = menuItemRepository.findAll().stream()
                    .filter(m -> name.equals(m.getName())).findFirst().orElseThrow();
            assertThat(saved.isDraft()).as("下書きになっていない").isTrue();
            // ★ 掲載も同時に落とすこと。draft だけ立てて visible を true のままにすると、
            //   visible を見ている問い合わせを素通りする
            assertThat(saved.isVisible()).as("下書きなのに掲載されている").isFalse();

            menuItemRepository.deleteById(saved.getId());
            categoryRepository.deleteById(c.getId());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("★ 掲載するときは価格が要る（空だとフォームに戻る）")
        void publishNeedsPrice() throws Exception {
            Category c = category();

            mockMvc.perform(post("/admin/items")
                            .param("action", "publish")
                            .param("categoryId", String.valueOf(c.getId()))
                            .param("name", "掲載したい品")
                            .param("price", "")
                            .with(csrf()))
                    // リダイレクトせずフォームを描き直す（打った内容を残すため）
                    .andExpect(status().isOk());

            assertThat(menuItemRepository.findAll())
                    .as("価格が空なのに掲載されてしまった")
                    .noneMatch(m -> "掲載したい品".equals(m.getName()));

            categoryRepository.deleteById(c.getId());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("カテゴリと商品名は下書きでも要る（DB が NOT NULL のため）")
        void draftStillNeedsNameAndCategory() throws Exception {
            mockMvc.perform(post("/admin/items")
                            .param("action", "draft")
                            .param("name", "")
                            .with(csrf()))
                    .andExpect(status().isOk());   // フォームに戻る
        }
    }

    @Nested
    @DisplayName("画面の作り")
    class 作り {

        @Test
        @DisplayName("★ 一覧のタブに「編集中」がある")
        void draftTabExists() throws Exception {
            String java = Files.readString(Path.of(
                    "src/main/java/jp/komeko/order/web/admin/AdminMenuItemController.java"));
            assertThat(java).contains("new ItemTab(\"draft\", \"編集中\", MenuItem::isDraft)");

            // 書きかけを掲載中・掲載停止・品切れのどれにも混ぜない。
            // 掲載停止に混ざると、季節外れで隠している品と作りかけが同じ棚に並ぶ
            assertThat(java).contains("item -> !item.isDraft() && item.isVisible()");
            assertThat(java).contains("item -> !item.isDraft() && !item.isVisible()");
        }

        @Test
        @DisplayName("★ 書きかけの価格 0 は「時価」ではなく「未入力」")
        void draftPriceIsNotMarketPrice() throws Exception {
            // 下書きは価格を空のまま保存できるので DB には 0 が入る。
            // そこに「時価」と出すと、値段を決めずに保存しただけの品が
            // 「その日の仕入れで決める品」に見える。意味がまるで違う
            String html = Files.readString(
                    Path.of("src/main/resources/templates/admin/items.html"));
            assertThat(html).contains("${item.price <= 0 and item.draft}\">未入力");
            assertThat(html).contains("${item.price <= 0 and !item.draft}\">時価");
        }

        @Test
        @DisplayName("★ 掲載できる条件がそろうまで「掲載する」は押せない")
        void publishButtonIsDisabledUntilReady() throws Exception {
            String html = Files.readString(
                    Path.of("src/main/resources/templates/admin/item-form.html"));
            // 商品名・カテゴリ・価格の 3 つ（設計の但し書きどおり）。
            // 隠さずに出しておくのは、そこに何があるかを先に見せるため
            assertThat(html).contains(
                    "th:disabled=\"${#strings.isEmpty(itemForm.name) or itemForm.categoryId == null or itemForm.price == null}\"");
        }

        @Test
        @DisplayName("詳しい設定は畳んであるだけで、消していない")
        void extraFieldsAreFoldedNotRemoved() throws Exception {
            String html = Files.readString(
                    Path.of("src/main/resources/templates/admin/item-form.html"));
            assertThat(html).contains("<details class=\"foldout");
            // 説明はお客さまのメニューに出るし、アレルゲンは表示義務がある
            for (String field : new String[]{"*{description}", "*{cookMinutes}", "*{recommended}", "allergens"}) {
                assertThat(html).as(field + " が消えている").contains(field);
            }
        }
    }
}
