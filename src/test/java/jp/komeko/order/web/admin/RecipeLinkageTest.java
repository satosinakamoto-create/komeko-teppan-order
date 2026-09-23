package jp.komeko.order.web.admin;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品とレシピを導線で縫う（2026-09-14、店主の指摘「レシピ・原価表は
 * 商品の中にあった方が良いんじゃない？」から）。
 *
 * <p><b>何が問題だったか。</b>商品の登録とレシピの登録が完全に別画面で、
 * 商品側からレシピへ飛ぶ道がゼロだった。登録漏れは「売れても在庫が減らない →
 * 『あと◯営業日』が甘く出る」という形で、黙って跳ね返る。
 *
 * <p><b>フォーム合体ではなく導線で縫う</b>と決めた理由：
 * レシピの要らない商品がある（瓶ビール・時価）／入力の重さが違う／
 * 在庫まわりは {@code app.inventory.enabled} で丸ごと切れるモジュール。
 * Figma はト10・トi12・トp12 とサイドバー部品を先に直してある。
 *
 * <p>3 本の縫い目：
 * <ol>
 *   <li>商品一覧に原価列（未登録は赤いリンク）… 漏れが一覧で見える</li>
 *   <li>追加直後の案内（レシピを登録する →）… 登録の一番の好機に入口を置く</li>
 *   <li>サイドバーでレシピ・原価表をメニュー管理へ … 商品の隣が住所</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("商品とレシピの導線")
class RecipeLinkageTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path ITEMS = Path.of("src/main/resources/templates/admin/items.html");
    private static final Path SIDEBAR = Path.of("src/main/resources/templates/layout/staff.html");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    // ---------------------------------------------------------------- ③ サイドバー

    /**
     * ★ 権限は変えないこと。メニュー管理の節は店長（ADMIN）専用の
     * {@code sec:authorize} の中にあるが、レシピ・原価表はいままで
     * スタッフにも見えていた（原価の列は画面側で隠している）。
     * ADMIN の塊の中へ入れると、スタッフのサイドバーから消えてしまう。
     * 並びだけ変えて、条件（inventoryEnabled）はそのまま持ち回る。
     */
    @Test
    @DisplayName("★ レシピ・原価表はカテゴリの後ろ・店舗の前（メニュー管理の並び）")
    void theRecipesLinkLivesWithTheMenuGroup() throws Exception {
        String t = withoutComments(read(SIDEBAR));
        int category = t.indexOf(">カテゴリ</span>");
        int recipes = t.indexOf(">レシピ・原価表</span>");
        int shop = t.indexOf(">店舗</p>");
        assertThat(category).isGreaterThan(0);
        assertThat(recipes).as("レシピ・原価表がカテゴリより前にある").isGreaterThan(category);
        assertThat(shop).as("レシピ・原価表が店舗の節より後ろにある").isGreaterThan(recipes);

        // 在庫モジュールを切ったら消える、はそのまま
        int anchor = t.lastIndexOf("<a", recipes);
        assertThat(t.substring(anchor, recipes)).contains("${inventoryEnabled}");
    }

    // ---------------------------------------------------------------- ① 原価列

    @Test
    @DisplayName("★ 一覧の原価列は価格の右。未登録は赤いリンクでレシピ編集へ")
    void theCostColumnShowsTheGap() throws Exception {
        String t = read(ITEMS);
        // ★ 2026-09-19：見出しのタグを丸ごと探すのをやめました。
        //   設計 ト10 の揃え方を当てるため、列見出しにクラスを付けています
        //   （<th class="col-price">価格（税込）</th>）。
        //   このテストが守りたいのは<b>列の順番</b>——原価が価格の右で掲載の左に
        //   あること——なので、文言の位置だけを見ます。
        int price = t.indexOf(">価格（税込）</th>");
        int cost = t.indexOf(">原価</th>");
        int listed = t.indexOf(">掲載</th>");
        assertThat(price).isGreaterThan(0);
        assertThat(cost).as("原価の列見出しが無い").isGreaterThan(price);
        assertThat(listed).isGreaterThan(cost);

        // 未登録：赤いリンクで、その品のレシピ編集へ直行
        assertThat(t).contains("未登録");
        assertThat(t).contains("/inventory/recipes/{id}(id=${item.id})");
        assertThat(t).contains("var(--danger)");

        // 在庫モジュールを切った店では列ごと出ない
        assertThat(t).contains("th:if=\"${itemCosts != null}\"");
    }

    /**
     * ★ 列幅は Figma ト10 の比どおり（280/200/140/120/130/130/120 を % に直した値）。
     *
     * <p><b>2026-09-20 に px から % へ変えました。</b>
     * px 固定だと、<b>縦スクロールバーが出た瞬間に</b>使える幅が 1120 → 1105px に減り、
     * 合計 1120px の表が <b>15px はみ出して横スクロールが生まれて</b>いました（実測）。
     * 商品は 100 品あって必ず縦に長くなるので、必ず起きます。
     *
     * <p>% なら比は設計のまま、幅が変わっても収まります。
     * 280/1120 = 25%、200/1120 = 17.857%、…という換算です。
     */
    @Test
    @DisplayName("★ 列幅は Figma ト10 の比どおり（25 / 17.857 / 12.5 / 10.714 / 11.607 / 11.607 / 10.714%）")
    void theColumnWidthsMatchTheMock() throws Exception {
        // 空白のゆらぎで落ちないよう、詰めてから見る
        String css = read(CSS).replaceAll("\s+", "");

        // ★ 2026-09-20：nth-child → クラス指定。
        //   原価の列は在庫モジュールを切ると列ごと消えるので、nth-child だと
        //   そこから右が 1 つずつ前にずれて別の列に当たります。
        // ★ 2026-09-20：並びの列を外して 7 → 6 列。空いた 118px を配り直しました
        //   （設計は倉庫の試作A）。商品名が 276 → 314px に広がっています。
        String[][] cols = {
            {"col-cat",   "20%"},      // 224 / 1120
            {"col-name",  "28.036%"},  // 314
            {"col-price", "13.929%"},  // 156
            {"col-cost",  "11.964%"},  // 134
            {"col-state", "13.036%"},  // 146（掲載）
            {"col-act",   "13.036%"},  // 146（編集）
        };
        for (String[] c : cols) {
            assertThat(css).as("table--items の %s の幅", c[0])
                    .contains(".table--itemsth." + c[0] + ",.table--itemstd." + c[0]
                            + "{width:" + c[1] + ";}");
        }

        // ★ px に戻さないこと。戻すと縦スクロールバーぶん 15px はみ出します
        assertThat(css)
                .as("★ 列幅が px に戻っている")
                .doesNotContain(".table--itemsth.col-name,.table--itemstd.col-name{width:280px;}");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧を開くと原価の列が出る（在庫モジュール有効のとき）")
    void theListRendersTheColumn() throws Exception {
        String html = mockMvc.perform(get("/admin/items"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(">原価</th>");
    }

    // ---------------------------------------------------------------- ② 追加直後の案内

    @Test
    @DisplayName("★ 追加直後の案内はレシピ編集へ直行できる（見出しの下の帯）")
    void theCtaBarLinksToTheRecipe() throws Exception {
        String t = read(ITEMS);
        assertThat(t).contains("flashNewItemId");
        assertThat(t).contains("レシピを登録すると、原価と在庫の減りを追えます");
        assertThat(t).contains("/inventory/recipes/{id}(id=${flashNewItemId})");
        assertThat(t).contains("レシピを登録する →");
        // 在庫モジュールを切った店では出さない（リンク先が 404 になる）
        int bar = t.indexOf("flashNewItemId != null");
        assertThat(bar).isGreaterThan(0);
        assertThat(t.substring(Math.max(0, bar - 200), bar)).contains("inventoryEnabled");
    }
}
