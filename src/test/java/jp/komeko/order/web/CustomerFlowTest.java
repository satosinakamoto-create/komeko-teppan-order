package jp.komeko.order.web;

import jp.komeko.order.domain.Allergen;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 画面（HTTP）レベルの結合テスト。
 *
 * <p><b>このテストが守っているもの</b>
 * <ol>
 *   <li><b>画面が本当に描けること</b>。Thymeleaf の式（{@code ${item.glutenFree}} など）は
 *       コンパイルされないので、getter 名を間違えても<b>ビルドは通ってしまい</b>、
 *       ブラウザで開いた瞬間に初めてエラーになります。
 *       画面を 1 度でも描かせておけば、その事故をテストで捕まえられます。</li>
 *   <li><b>アクセス制御が効いていること</b>。厨房・管理画面が誰でも見られたら、
 *       他のお客さんの注文情報も丸見えになります。</li>
 *   <li><b>CSRF 対策が効いていること</b>。他サイトから勝手に POST されないようにする仕組みです。</li>
 * </ol>
 *
 * <p><b>MockMvc とは</b><br>
 * 実際に Tomcat を起動してポートを開かずに、
 * 「HTTP リクエストが来たことにして」コントローラを動かす仕組みです。
 * ブラウザを立ち上げるテストより桁違いに速く、それでいて
 * セキュリティフィルタも Thymeleaf の描画も本番と同じものが動きます。
 *
 * <p><b>{@code @WithMockUser} の注意点</b><br>
 * ログイン済みのふりをするアノテーションですが、作られるのは Spring Security 標準の
 * ユーザーオブジェクトで、このアプリ独自の {@code StaffUserDetails} ではありません。
 * そのためコントローラの {@code @AuthenticationPrincipal StaffUserDetails} は null になります。
 * ここでは<b>「入れるか／弾かれるか」だけ</b>を確かめ、
 * 画面の中身の検証はお客さん向けページに任せています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("画面まわり（HTTPレベル）")
class CustomerFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    /** テストで使う商品の ID。 */
    private Long menuItemId;

    /**
     * 表示できるメニューを 1 品だけ用意する。
     *
     * <p>このクラスには {@code @Transactional} を付けていません。
     * MockMvc のリクエストはコントローラ側で独自にトランザクションを張るため、
     * テストメソッドのトランザクションで囲ってしまうと、
     * 「テストからは見えるがリクエストからは見えない」といったねじれが起きやすいからです。
     * そのかわり、作ったデータは {@link #tearDown()} で自分で片付けます。
     */
    @BeforeEach
    void setUp() {
        clearMenu();

        Category category = categoryRepository.save(new Category("米粉ガレット", 10));
        MenuItem item = new MenuItem(category, "コンプレット", 880);
        item.setDescription("定番の卵・チーズ・ハム。米粉100%の生地を鉄板でパリッと。");
        item.setCookMinutes(8);
        item.setRecommended(true);
        // 小麦を含まない＝「小麦不使用」バッジが出る経路も描画させる
        item.setAllergens(EnumSet.of(Allergen.EGG, Allergen.MILK));
        menuItemId = menuItemRepository.save(item).getId();
    }

    @AfterEach
    void tearDown() {
        clearMenu();
    }

    private void clearMenu() {
        // 商品 → カテゴリ の順に消す。逆順だと外部キー制約で削除できない。
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Nested
    @DisplayName("お客さん向けの画面（ログイン不要）")
    class CustomerPages {

        @Test
        @DisplayName("GET / でメニュー画面が 200 で描画される")
        void menuPageRenders() throws Exception {
            // status が 200 なだけでなく、view の描画まで実行される。
            // Thymeleaf の式が実在しない getter を指していれば、ここで例外になって落ちる。
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/menu"))
                    .andExpect(model().attributeExists("menu", "accepting", "shop"))
                    // 用意した商品が実際に HTML に出ていること
                    .andExpect(content().string(containsString("コンプレット")))
                    .andExpect(content().string(containsString("米粉ガレット")));
        }

        @Test
        @DisplayName("GET /items/{id} で商品詳細が 200 で描画される")
        void itemPageRenders() throws Exception {
            mockMvc.perform(get("/items/{id}", menuItemId))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/item"))
                    .andExpect(model().attributeExists("item"));
        }

        @Test
        @DisplayName("GET /cart でカート画面が 200 で描画される")
        void cartPageRenders() throws Exception {
            mockMvc.perform(get("/cart"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/cart"));
        }

        @Test
        @DisplayName("存在しない注文トークン /o/xxxx は 404 になる")
        void unknownOrderTokenIsNotFound() throws Exception {
            // 注文控えの URL は推測しにくいトークン方式。
            // 当てずっぽうのトークンで他人の注文が見えないことと、
            // 500 エラーではなくきちんと 404 の案内ページが出ることを確認する。
            mockMvc.perform(get("/o/{token}", "no-such-token-1234"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/message"));
        }
    }

    @Nested
    @DisplayName("カートへの追加と CSRF 対策")
    class CartPost {

        @Test
        @DisplayName("CSRF トークン付きの POST /cart/add は /cart へリダイレクトされる")
        void addToCartRedirects() throws Exception {
            // 更新系の POST は処理後に必ずリダイレクトする（PRG パターン）。
            // そのまま HTML を返すと、ブラウザの再読み込みで二重に追加されてしまう。
            mockMvc.perform(post("/cart/add")
                            .with(csrf())
                            .param("menuItemId", String.valueOf(menuItemId))
                            .param("quantity", "2"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/cart"));
        }

        @Test
        @DisplayName("CSRF トークンが無い POST は 403 で拒否される")
        void postWithoutCsrfIsForbidden() throws Exception {
            // これが「素の <form action=...> を書くと 403 になる」正体。
            // Thymeleaf の th:action を使えば隠しトークンが自動で入るので普段は意識しなくてよい。
            // 悪意あるサイトに勝手にフォームを置かれて注文されるのを防いでいる。
            mockMvc.perform(post("/cart/add")
                            .param("menuItemId", String.valueOf(menuItemId))
                            .param("quantity", "1"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しない商品をカートに入れようとすると 404 になる")
        void addUnknownItem() throws Exception {
            // 画面に出ていない商品 ID を直接 POST されても、サーバ側で弾けること。
            mockMvc.perform(post("/cart/add")
                            .with(csrf())
                            .param("menuItemId", "999999")
                            .param("quantity", "1"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("店側の画面のアクセス制御")
    class StaffAccessControl {

        @Test
        @DisplayName("未ログインで GET /kitchen を開くとログイン画面へリダイレクトされる")
        void kitchenRequiresLogin() throws Exception {
            // 厨房画面には他のお客さんの注文がすべて並ぶ。
            // 誰でも見られたら個人情報（呼び出し名・要望）が漏れてしまう。
            mockMvc.perform(get("/kitchen"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @WithMockUser(roles = "STAFF")
        @DisplayName("STAFF 権限では GET /admin が 403 になる")
        void staffCannotOpenAdmin() throws Exception {
            // 売上・スタッフ管理はアルバイトには見せない。
            // 「ログインしている＝何でもできる」にしないための権限分けが効いているか確認する。
            mockMvc.perform(get("/admin"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("ADMIN 権限なら GET /admin が認可で弾かれない")
        void adminCanOpenAdmin() throws Exception {
            MvcResult result = mockMvc.perform(get("/admin")).andReturn();
            int statusCode = result.getResponse().getStatus();

            // ここで「200 ちょうど」を要求していないのは、@WithMockUser で作られるのが
            // 標準のユーザーオブジェクトで、@AuthenticationPrincipal StaffUserDetails が
            // null になるためです（クラスの Javadoc 参照）。
            // このテストの目的はあくまで「ADMIN なら認可で弾かれないこと」の確認なので、
            // 403（権限なし）・401（未認証）・302（ログイン画面へ飛ばされた）でないことを見ます。
            assertThat(statusCode)
                    .as("ADMIN 権限なら認可で拒否されないこと（実際の応答: %d）", statusCode)
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value(),
                            HttpStatus.FOUND.value());
        }

        @Test
        @DisplayName("未ログインでも店内サイネージ /display は開ける")
        void displayIsPublic() throws Exception {
            // 大画面に出しっぱなしにするので、ログインを要求すると運用できない。
            // 番号しか出さないので公開しても問題にならない、という設計判断。
            MvcResult result = mockMvc.perform(get("/display")).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("サイネージはログイン不要で開けること")
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value(),
                            HttpStatus.FOUND.value());
        }

        @Test
        @DisplayName("ログイン画面は誰でも開ける")
        void loginPageIsPublic() throws Exception {
            MvcResult result = mockMvc.perform(get("/login")).andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("ログイン画面自体は公開されていること")
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value(),
                            HttpStatus.FOUND.value());
        }
    }
}
