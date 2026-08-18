package jp.komeko.order.web;

import jp.komeko.order.domain.Allergen;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumSet;
import java.util.List;

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
 *   <li><b>卓の QR からの導線がつながっていること</b>。イートインでは
 *       「QR を読む → 人数を答える → 伝票が開く → メニューが出る」の順番でしか
 *       注文できません。どこか 1 つでも切れると、お客さんは注文できません。</li>
 *   <li><b>画面が本当に描けること</b>。Thymeleaf の式（{@code ${bill.totalAmount}} など）は
 *       コンパイルされないので、getter 名を間違えても<b>ビルドは通ってしまい</b>、
 *       ブラウザで開いた瞬間に初めてエラーになります。
 *       画面を 1 度でも描かせておけば、その事故をテストで捕まえられます。</li>
 *   <li><b>アクセス制御が効いていること</b>。厨房・ホール・管理画面が誰でも見られたら、
 *       他のお客さんの注文や売上まで丸見えになります。</li>
 *   <li><b>CSRF 対策が効いていること</b>。他サイトから勝手に POST されないようにする仕組みです。</li>
 * </ol>
 *
 * <p><b>MockMvc とは</b><br>
 * 実際に Tomcat を起動してポートを開かずに、
 * 「HTTP リクエストが来たことにして」コントローラを動かす仕組みです。
 * ブラウザを立ち上げるテストより桁違いに速く、それでいて
 * セキュリティフィルタも Thymeleaf の描画も本番と同じものが動きます。
 *
 * <p><b>{@link MockHttpSession} を使う理由</b><br>
 * 「いまどの卓にいるか」（{@code TableContext}）と「カート」（{@code Cart}）は
 * {@code @SessionScope} の Bean で、ブラウザのセッションに保存されます。
 * MockMvc は何も指定しないとリクエストごとに新しいセッションを作るので、
 * <b>同じセッションを使い回したいときは自分で {@code MockHttpSession} を渡します</b>。
 * これが「QR を読んだ人が、そのままメニューを見る」を再現する方法です。
 *
 * <p><b>{@code @WithMockUser} の注意点</b><br>
 * ログイン済みのふりをするアノテーションですが、作られるのは Spring Security 標準の
 * ユーザーオブジェクトで、このアプリ独自の {@code StaffUserDetails} ではありません。
 * そのためコントローラの {@code @AuthenticationPrincipal StaffUserDetails} は null になります。
 * ここでは<b>「入れるか／弾かれるか」</b>を中心に確かめています。
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
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;

    /** テストで使う商品の ID。 */
    private Long menuItemId;
    /** テストで使う卓の QR トークン。 */
    private String accessToken;

    /**
     * 卓を 1 つと、表示できるメニューを 1 品だけ用意する。
     *
     * <p>このクラスには {@code @Transactional} を付けていません。
     * MockMvc のリクエストはコントローラ側で独自にトランザクションを張るため、
     * テストメソッドのトランザクションで囲ってしまうと、
     * 「テストからは見えるがリクエストからは見えない」といったねじれが起きやすいからです。
     * そのかわり、作ったデータは {@link #tearDown()} で自分で片付けます。
     */
    @BeforeEach
    void setUp() {
        clearAll();

        Category category = categoryRepository.save(new Category("広島風お好み焼き", 10));
        MenuItem item = new MenuItem(category, "肉玉米粉そば", 1180);
        item.setDescription("定番。豚肉と卵、米粉そば入り。鉄板でふっくら焼き上げます。");
        item.setCookMinutes(12);
        item.setRecommended(true);
        // アレルゲンのバッジが出る経路も描画させる
        item.setAllergens(EnumSet.of(Allergen.EGG, Allergen.MILK));
        menuItemId = menuItemRepository.save(item).getId();

        // 卓の QR トークンはコンストラクタの中でランダムに作られる
        accessToken = diningTableRepository.save(new DiningTable("1番テーブル", 4, 10)).getAccessToken();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    /**
     * 作ったデータを消す。
     *
     * <p>消す順番には理由があります。注文 → 伝票 → 卓、商品 → カテゴリ の順に、
     * <b>参照している側から先に</b>消さないと外部キー制約で削除できません。
     */
    private void clearAll() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        diningTableRepository.deleteAll();
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Nested
    @DisplayName("卓の QR からの導線（ログイン不要）")
    class TableEntry {

        @Test
        @DisplayName("QR を読んでいない状態で GET / を開くと「お席の QR をお読みください」になる")
        void menuIsHiddenWithoutTable() throws Exception {
            // ブックマークや検索から直接来た人がここに来る。
            // どの席か分からないまま注文させると、料理をどこへ運ぶか決まらない。
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/no-table"));
        }

        @Test
        @DisplayName("存在しない QR トークン /t/xxxx は 404 になる")
        void unknownTokenIsNotFound() throws Exception {
            // トークンは推測できないランダム文字列。
            // 当てずっぽうのトークンで別の卓の伝票が開けないこと、
            // そして 500 エラーではなくきちんと案内ページが出ることを確認する。
            //
            // 【申し送り・既知の不具合】2026-08-16 のレビュー時点で、このテストは落ちます。
            //   GlobalExceptionHandler が 404 に変換しているのは
            //     MenuService.MenuItemNotFoundException
            //     OrderService.OrderNotFoundException
            //   の 2 つだけで、TableService.TableNotFoundException が入っていません。
            //   そのため未知のトークンは 404 ではなく 500（例外がそのまま外へ）になります。
            //   直し方（GlobalExceptionHandler の担当へ）:
            //     handleNotFound の @ExceptionHandler に
            //     TableService.TableNotFoundException.class を足す
            //     （合わせて TableService.SessionNotFoundException.class も）。
            //   「知らない QR を読んだら案内ページ」はお客さんに見せるべき正しい挙動なので、
            //   期待値は 404 のまま残します（テスト側を 500 に合わせて直さないこと）。
            mockMvc.perform(get("/t/{token}", "no-such-token-1234"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/message"));
        }

        @Test
        @DisplayName("正しい QR トークンなら人数の確認画面が 200 で描画される")
        void tokenOpensGuestCountPage() throws Exception {
            mockMvc.perform(get("/t/{token}", accessToken))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/table-start"))
                    .andExpect(model().attributeExists("table", "accepting"))
                    .andExpect(content().string(containsString("1番テーブル")));
        }

        @Test
        @DisplayName("人数を送ると伝票が開き、そのままメニューが見られるようになる")
        void startingSessionUnlocksTheMenu() throws Exception {
            // ★このクラスでいちばん大事なテスト★
            // 「QR を読む → 人数を答える → メニュー」がイートインの全導線。
            // 同じお客さんの操作として続けて見るために、セッションを使い回す。
            //
            // 【申し送り・既知の不具合】2026-08-16 時点で、最後の GET / が
            //   LazyInitializationException で 500 になります（イートイン化以前からの不具合）。
            //   原因は MenuItemRepository#findVisibleForCustomer の
            //     @EntityGraph(attributePaths = {"category"})
            //   です。Spring Data の @EntityGraph は既定が「フェッチグラフ」で、
            //   <b>グラフに書かなかった関連は EAGER 指定でも LAZY として扱われます</b>。
            //   そのため MenuItem.allergens（@ElementCollection(fetch = EAGER)）が
            //   未初期化のまま画面に渡り、open-in-view: false なので描画中に落ちます。
            //   落ちる場所は fragments/common の allergens フラグメント（"!item.allergens.isEmpty()"）です。
            //   直し方はどちらかです（メニュー担当へ）:
            //     ・attributePaths に "allergens" を足す
            //     ・MenuService#customerMenu の中で allergens を実体化しておく（hydrate）
            //   ※ 2026-08-16 のレビューでも再現を確認済み。お客さんのメニュー画面が
            //     まるごと 500 になる不具合なので、テスト側は 200 の期待のまま残します。
            MockHttpSession browserSession = new MockHttpSession();

            // 更新系の POST には必ず CSRF トークンを付ける（本番では th:action が自動で入れる）
            mockMvc.perform(post("/t/{token}/start", accessToken)
                            .session(browserSession)
                            .with(csrf())
                            .param("guestCount", "2"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));

            // 伝票（来店）が 1 枚だけ立っている。
            // findByStatus... は @EntityGraph で卓も一緒に読むので、
            // トランザクションの外でも卓名を触れる（LazyInitializationException にならない）。
            List<TableSession> bills =
                    tableSessionRepository.findByStatusOrderByOpenedAtAsc(SessionStatus.OPEN);
            assertThat(bills).hasSize(1);
            assertThat(bills.get(0).getGuestCount()).isEqualTo(2);
            assertThat(bills.get(0).getDiningTable().getName()).isEqualTo("1番テーブル");
            // テーブルチャージは着席した時点で計上される
            assertThat(bills.get(0).getTableChargeAmount())
                    .isEqualTo(bills.get(0).getTableChargePerGuest() * 2);

            // 同じセッションで開けば、今度はメニューが出る
            mockMvc.perform(get("/").session(browserSession))
                    .andExpect(status().isOk())
                    .andExpect(view().name("customer/menu"))
                    .andExpect(model().attributeExists("menu", "accepting", "shop"))
                    .andExpect(content().string(containsString("肉玉米粉そば")))
                    .andExpect(content().string(containsString("広島風お好み焼き")));
        }

        @Test
        @DisplayName("伝票を開いたあとに QR を読み直しても、伝票は増えずメニューへ戻される")
        void readingQrAgainGoesBackToMenu() throws Exception {
            // 食事中にうっかり QR を読み直す、というのはよくある。
            // そのたびに人数を聞き直していると、伝票が二重に立ちかねない。
            MockHttpSession browserSession = new MockHttpSession();

            mockMvc.perform(post("/t/{token}/start", accessToken)
                    .session(browserSession)
                    .with(csrf())
                    .param("guestCount", "2"));

            mockMvc.perform(get("/t/{token}", accessToken).session(browserSession))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));

            assertThat(tableSessionRepository.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("お客さん向けのその他の画面")
    class CustomerPages {

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
        @DisplayName("卓が紐づいていない状態で GET /bill を開くと入口へ戻される")
        void billRedirectsWhenNoTable() throws Exception {
            // 伝票は「その卓のお客さん」だけのもの。
            // 卓が分からない状態で開けてしまうと、どの伝票を見せるかも決められない。
            mockMvc.perform(get("/bill"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
        }
    }

    @Nested
    @DisplayName("注文リストへの追加と CSRF 対策")
    class CartPost {

        @Test
        @DisplayName("追加したらメニューへ戻す（続けて選べるように）")
        void addToCartRedirects() throws Exception {
            // 更新系の POST は処理後に必ずリダイレクトする（PRG パターン）。
            // そのまま HTML を返すと、ブラウザの再読み込みで二重に追加されてしまう。
            //
            // 戻し先はメニュー。飲食店では「いくつか見て回って、最後にまとめて注文する」ので、
            // 1 品足すたびに注文リストへ連れて行くと、そのたびに戻る操作が要る。
            // （2026-08-18 に /cart から /menu へ変更。体験を決める 1 行なのでテストで固定する）
            mockMvc.perform(post("/cart/add")
                            .with(csrf())
                            .param("menuItemId", String.valueOf(menuItemId))
                            .param("quantity", "2"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/menu"));
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
            // 厨房画面には全卓の注文が並ぶ。
            // 誰でも見られたら、他のお客さんの注文内容や要望まで漏れてしまう。
            mockMvc.perform(get("/kitchen"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @DisplayName("未ログインで GET /hall を開くとログイン画面へリダイレクトされる")
        void hallRequiresLogin() throws Exception {
            // ホール画面は会計そのもの。誰でも開けたら、
            // 他の卓の金額を見られるうえに、勝手に会計を締められてしまう。
            mockMvc.perform(get("/hall"))
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
        @DisplayName("ADMIN 権限なら GET /admin が 200 で描画される")
        void adminCanOpenAdmin() throws Exception {
            mockMvc.perform(get("/admin"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/home"));
        }

        @Test
        @DisplayName("GET /login はログインしていなくても 200 で描画される")
        void loginPageRenders() throws Exception {
            // ここが 404 や無限リダイレクトになると、スタッフは誰もログインできない。
            // ログイン画面は WebConfig の addViewControllers で登録されている。
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("login"));
        }
    }
}
