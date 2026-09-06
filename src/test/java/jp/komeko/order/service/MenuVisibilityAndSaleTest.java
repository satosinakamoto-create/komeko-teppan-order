package jp.komeko.order.service;

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
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品一覧から「掲載」「販売」を切り替える（2026-09-07 / 設計 08 商品）。
 *
 * <p><b>いちばん守っているのは ¥0 で売れないことです。</b><br>
 * {@link MenuItem#isOrderable()} は価格を見ません。掲載中かつ品切れでなければ
 * 注文できます。時価の品は<b>価格 0 で登録し、品切れにしておく</b>ことで
 * お客さまから注文できない状態にしています（金額はスタッフが店舗端末で決める）。
 *
 * <p>つまり品切れを外すと ¥0 のまま注文できる品ができ、売上にも伝票にも
 * 0 円で乗ります。気づくのは会計のときです。
 * この危険があるために、同じボタンは 2026-08-27 に一度<b>消されました</b>。
 * 戻すにあたって、ボタンではなく危険のほうを {@link MenuService#toggleSoldOut} で
 * 閉じています。1 か所で閉じたので、厨房の品切れパネルからも同じように守られます。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品一覧からの掲載・販売の切り替え")
class MenuVisibilityAndSaleTest {

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuService menuService;
    @Autowired
    private MockMvc mockMvc;

    private MenuItem item(int price) {
        Category category = categoryRepository.save(new Category("切替テスト" + System.nanoTime(), 999));
        return menuItemRepository.save(new MenuItem(category, "テスト品" + System.nanoTime(), price));
    }

    @Nested
    @Transactional
    @DisplayName("業務ルール")
    class ルール {

        @Test
        @DisplayName("★ 価格が入っていない品は販売を再開できない（¥0 で売れてしまう）")
        void cannotResumeSaleWithoutPrice() {
            MenuItem 時価 = item(0);
            時価.setSoldOut(true);

            assertThatThrownBy(() -> menuService.toggleSoldOut(時価.getId()))
                    .isInstanceOf(MenuService.PriceNotSetException.class)
                    .hasMessageContaining(時価.getName());

            // 断ったあとも状態は動いていないこと。
            // 例外は投げたのにフラグだけ変わっている、が起きると
            // 画面には「できません」と出ているのに実際は売れる状態になる
            assertThat(menuItemRepository.findById(時価.getId()).orElseThrow().isSoldOut())
                    .as("断ったのに品切れが外れている").isTrue();
        }

        @Test
        @DisplayName("★ 価格が無くても『品切れにする』側は通す（安全な向き）")
        void canStopSellingWithoutPrice() {
            MenuItem 時価 = item(0);
            時価.setSoldOut(false);

            // 止めるほうを断ると、¥0 で売れている品を止められなくなる。
            // 危険なのは再開の向きだけなので、こちらは通す
            assertThat(menuService.toggleSoldOut(時価.getId())).isTrue();
        }

        @Test
        @DisplayName("価格のある品はふつうに行き来できる")
        void pricedItemTogglesBothWays() {
            MenuItem 品 = item(1180);
            assertThat(menuService.toggleSoldOut(品.getId())).as("品切れにできない").isTrue();
            assertThat(menuService.toggleSoldOut(品.getId())).as("再開できない").isFalse();
        }

        @Test
        @DisplayName("掲載は価格を見ない（掲載しても品切れなら注文はできない）")
        void visibilityDoesNotCheckPrice() {
            MenuItem 時価 = item(0);
            時価.setVisible(false);
            時価.setSoldOut(true);

            assertThat(menuService.toggleVisible(時価.getId())).isTrue();
            // 掲載しただけでは注文できるようにならない
            assertThat(menuItemRepository.findById(時価.getId()).orElseThrow().isOrderable())
                    .as("掲載しただけで注文できてしまう").isFalse();
        }
    }

    @Nested
    @DisplayName("画面から")
    class 画面 {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("★ 押した場所（タブと検索語）へ戻る")
        void goesBackToWhereYouPressed() throws Exception {
            MenuItem 品 = item(880);

            mockMvc.perform(post("/admin/items/{id}/sale", 品.getId())
                            .param("tab", "onsale").param("q", "ホタテ").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    // 94 品ある画面なので、素の一覧に戻すと毎回いちばん上に着地して
                    // 続けて作業できない
                    .andExpect(redirectedUrl("/admin/items?tab=onsale&q=%E3%83%9B%E3%82%BF%E3%83%86"));

            menuItemRepository.deleteById(品.getId());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("掲載も切り替えられる")
        void visibilityEndpointWorks() throws Exception {
            MenuItem 品 = item(880);
            assertThat(品.isVisible()).isTrue();

            mockMvc.perform(post("/admin/items/{id}/visibility", 品.getId()).with(csrf()))
                    .andExpect(status().is3xxRedirection());

            assertThat(menuItemRepository.findById(品.getId()).orElseThrow().isVisible())
                    .as("掲載が止まっていない").isFalse();

            menuItemRepository.deleteById(品.getId());
        }
    }

    @Nested
    @DisplayName("画面の作り")
    class 作り {

        private static final Path ITEMS_HTML =
                Path.of("src/main/resources/templates/admin/items.html");
        private static final Path APP_CSS =
                Path.of("src/main/resources/static/css/app.css");

        @Test
        @DisplayName("★ 掲載と販売はバッジの形のまま押せる（設計 08 商品）")
        void badgesArePressable() throws Exception {
            String html = Files.readString(ITEMS_HTML).replaceAll("(?s)<!--.*?-->", "");

            // 設計でもこの 2 列はバッジで、ボタンの形はしていない。
            // 形を変えずに押せるようにする（状態を見る場所と変える場所を同じにする）
            assertThat(html).as("掲載を切り替える口が無い").contains("/admin/items/{id}/visibility");
            assertThat(html).as("販売を切り替える口が無い").contains("/admin/items/{id}/sale");
            assertThat(html).as("バッジの形を捨てている").contains("class=\"badge badge--act badge--gf\"");

            // 押した場所へ戻すために、タブと検索語を持たせる
            assertThat(html).contains("name=\"tab\" th:value=\"${currentTab}\"");
            assertThat(html).contains("name=\"q\" th:value=\"${q}\"");
        }

        @Test
        @DisplayName("★ 残数ゼロの品切れは押せない（押しても次の注文で 0 に戻る）")
        void outOfStockIsNotPressable() throws Exception {
            String html = Files.readString(ITEMS_HTML).replaceAll("(?s)<!--.*?-->", "");

            // 手で立てたフラグ（soldOut）だけがボタン。
            // 残数ゼロ（outOfStock）は数の結果なので、ここで外しても意味が無い
            assertThat(html).contains("th:if=\"${item.soldOut}\"");
            assertThat(html).contains("th:if=\"${!item.soldOut and item.outOfStock}\"");
        }

        @Test
        @DisplayName("★ 見出し・探す・表のあいだは 48px（設計 17:965 の gap-48）")
        void fortyEightBetweenBlocks() throws Exception {
            // 箱そのものの寸法（高さ 64・左右 12・角丸 6）は前から設計どおりで、
            // ずれていたのは前後の間だけだった（実測 0px）。
            // 並べて見ないと気づけない類のずれ
            String css = Files.readString(APP_CSS);
            assertThat(css).as("探すの上が詰まっている").contains(".searchbox { margin-top: 48px; }");
            assertThat(css).as("表の上が詰まっている").contains(".tabtable  { margin-top: 48px; }");

            // ★ .staff-main を flex + gap にしないこと。
            //   全管理画面が通る器なので、各画面の mt-4 などと足し算になる
            int at = css.indexOf(".theme-desk .staff-main   {");
            assertThat(at).isGreaterThan(-1);
            assertThat(css.substring(at, css.indexOf('}', at)))
                    .as("共通の器に gap を持たせている").doesNotContain("gap:");
        }
    }
}
