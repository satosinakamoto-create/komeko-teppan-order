package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.repository.IngredientRepository;
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
 * 食材・在庫の作り直し（設計 現04 441:2715）と、
 * 棚卸し・廃棄の記録ページ（設計 現05 443:2940）。2026-09-07。
 *
 * <p><b>何を変えたか</b><br>
 * 一覧のいちばん下にあった 2 つの記録フォームを専用ページへ移し、
 * 一覧には検索を足しました。一覧は「いま何がどれだけあるか」を読む画面、
 * 記録は仕込みの前後にまとめてやる別の仕事、という切り分けです。
 *
 * <p><b>いちばん守っているのは記録の往復</b>です。
 * 仕込み後は何品も続けて記録するので、1 件記録するたびに一覧へ
 * 飛ばされたり、選んでいた食材が外れたりすると作業になりません。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("食材・在庫と記録ページ（現04 / 現05）")
class StockRecordPageTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredients;

    private static final Path LIST =
            Path.of("src/main/resources/templates/inventory/ingredients.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧に記録フォームが無く、記録ページへの口がある")
    void listHasNoFormsButLinks() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("記録導線テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        String html = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String main = html.substring(html.indexOf("<main"), html.lastIndexOf("</main>"));

        // 記録フォームは専用ページへ移した。両方に残すと、直した片方だけが
        // 生きているように見える（同じフォームが 2 か所で違う動きをする）
        assertThat(main).as("一覧に棚卸しフォームが残っている")
                .doesNotContain("/inventory/ingredients/stocktake");
        assertThat(main).as("記録ページへの口が無い")
                .contains("/inventory/ingredients/record?ingredient=" + i.getId());

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 記録ページは、指定した食材が選ばれた状態で開く")
    void recordPagePreselectsTheIngredient() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("選択テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        String html = mockMvc.perform(get("/inventory/ingredients/record")
                        .param("ingredient", String.valueOf(i.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("棚卸し（数え直す）");
        assertThat(html).contains("廃棄・まかない（減らす）");
        // th:field は一致した option に selected を付ける。
        // 一覧の「記録する」から来た人に食材を選び直させないための要。
        // ★ 2 つのカードの両方で選ばれていること。片方だけだと、
        //   棚卸しのつもりで廃棄カードに書いて別の食材を減らす事故になる
        String selected = "value=\"" + i.getId() + "\" selected";
        assertThat(html.split(java.util.regex.Pattern.quote(selected), -1).length - 1)
                .as("棚卸しと廃棄の両方で食材が選ばれていない").isEqualTo(2);

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 記録するは枠付きのボタン（設計 466:6111）")
    void recordIsAButtonNotAPlainLink() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("ボタンテスト-" + System.nanoTime(), IngredientUnit.GRAM));

        String html = mockMvc.perform(get("/inventory/ingredients"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 文字リンクのままだと、同じ行にある食材名のリンクと見分けが付かない
        assertThat(html).as("ボタンになっていない").contains("class=\"recbtn\"");
        assertThat(html).doesNotContain("class=\"reclink\"");
        // どの食材の記録なのかを読み上げでも分かるようにする
        assertThat(html).contains("の棚卸し・廃棄を記録する");
        // 列の名前。空のままだとボタンだけが並んで、何の列か読み取れない
        assertThat(html).contains(">棚卸・廃棄</th>");

        // ★ 2026-09-17 に色と寸法を直しました（店主の指摘「編集ボタンが昔のまま」）。
        //   --accent は :root では黒だが .theme-desk がティールに上書きするため、
        //   スタッフ側のこのボタンだけ旧色で残っていた。設計（ト09 731:4147）は
        //   枠も文字も #0b7a1a＝--action。
        //   高さ 54 → 48（設計は 34px だが CLAUDE.md の 48px 床を優先）。
        //   幅は 100% をやめた。設計は列幅いっぱいの帯ではなく 60px の小さなボタン。
        String css = Files.readString(CSS).replace("\r\n", "\n");
        assertThat(css).contains("  height: 48px;");
        assertThat(css).contains("  border: 1px solid var(--action);");
        assertThat(css).contains(".recbtn:hover { background: var(--action-soft); }");

        ingredients.deleteById(i.getId());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 記録したあとは記録ページに留まる（食材の選択も保つ）")
    void recordingStaysOnTheRecordPage() throws Exception {
        Ingredient i = ingredients.save(
                new Ingredient("往復テスト-" + System.nanoTime(), IngredientUnit.GRAM));

        mockMvc.perform(post("/inventory/ingredients/adjust")
                        .param("origin", "record")
                        .param("ingredientId", String.valueOf(i.getId()))
                        .param("takenOn", "2026-09-07")
                        .param("quantity", "50")
                        .param("reason", "WASTE")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 一覧へ飛ばすと、続けて記録する人が毎回開き直すことになる
                .andExpect(redirectedUrl("/inventory/ingredients/record?ingredient=" + i.getId()));

        // ★ この食材は消さない。いま記録した棚卸しの行が参照しているので、
        //   消すと外部キー違反で落ちる（実際に落とした）。
        //   名前に nanoTime が入っているため、残しても他のテストとは衝突しない
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 名前で探せる（部分一致・他の食材は出ない）")
    void searchFiltersByName() throws Exception {
        long n = System.nanoTime();
        Ingredient hit = ingredients.save(new Ingredient("検索ヒット米粉-" + n, IngredientUnit.GRAM));
        Ingredient miss = ingredients.save(new Ingredient("検索ハズレ卵-" + n, IngredientUnit.PIECE));

        String html = mockMvc.perform(get("/inventory/ingredients").param("q", "検索ヒット"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("検索ヒット米粉-" + n);
        assertThat(html).as("絞り込めていない").doesNotContain("検索ハズレ卵-" + n);

        ingredients.deleteById(hit.getId());
        ingredients.deleteById(miss.getId());
    }

    @Test
    @DisplayName("★ 件数の知らせは出さない（バッジが同じ情報を持っている）")
    void attentionAlertIsGone() throws Exception {
        // 設計 現04 で削除された。知らせは行の「少ない」バッジの件数を
        // 言い直していただけで、消しても情報は失われない
        String html = Files.readString(LIST).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html).doesNotContain("の食材が少なくなっているか");
        // バッジのほうは残っていること（こちらを消すと本当に情報が消える）
        assertThat(html).contains(">少ない</span>");
        assertThat(html).contains(">マイナス</span>");
    }

    @Test
    @DisplayName("★ 画面の寸法（py32・検索48・行の上下16）が設計値のまま")
    void designMetrics() throws Exception {
        String css = Files.readString(CSS);

        // ★ 2026-09-17：縦の余白を画面ごとに変えるのをやめました（店主の指摘）。
        //   ここは 2026-09-13 に「Figma 01 ページでこの画面は 64px だった」を根拠に
        //   :has で 64px にしていましたが、01 ページは古い版です。
        //   いま正としている 07 ページで測り直すと、食材 32／レシピ 64／
        //   バックアップ 64／商品 64／品切れ 32／売上 64 と設計自体がばらついていて
        //   根拠になりませんでした。基準の 32px に統一しています
        //   （TopGapIsConsistentTest が全画面ぶんを見ています）。
        //
        //   見出しの目印（.inv-ingredients）は、帯の寸法に今も使うので残します。
        assertThat(Files.readString(LIST)).contains("section-title inv-ingredients");
        assertThat(css).contains(".stockpage .inv-ingredients {");

        // 探す欄は素の .searchbox。この画面だけの版（--slim）は持たない。
        // 規則そのものを見る。裸の ".searchbox--slim" だと、外した経緯を書いた
        // コメントに一致して必ず落ちる（card__head の件と同じ罠）
        // ★ 高さは 64 → 48（2026-09-13、店主指示で統一）。
        //   設計 466:5915 は 64 でしたが、品切れだけ 48 で 2 種類あったのを
        //   ボタン・タップの床と同じ 48 に一本化した（SearchBoxHeightTest が正）
        assertThat(css).doesNotContain(".searchbox--slim {");
        assertThat(Files.readString(LIST)).doesNotContain("searchbox--slim");
        // しぼり込みは 2 つ並び、間は 42（設計 466:5915）
        assertThat(css).contains(".stockpage .stockfind { gap: 42px; margin-top: 0; }");
        // 見出しは左右 40・上下 16、ボタンは右端
        assertThat(css).contains(".stockpage .inv-ingredients .btn { margin-left: auto; }");

        // 行の上下 9（2026-09-07 に 16 から変更）。
        // 「記録する」がボタン（高さ 54）になり、行の高さはボタンで決まるようになった。
        // 16 のままだと 86px になって、1 画面に入る食材が 2 つ減る。
        // ★ :has で絞ること。同じ .table--stock を「未学習のレシート品名」の
        //   表も使っていて、あちらにはボタンが無い。絞らないと理由もなく詰まる
        assertThat(css).contains(".theme-desk .table--stock:has(.recbtn) td { padding-top: 9px; padding-bottom: 9px; }");
        assertThat(css).as("ボタンの無い表まで詰めている")
                .contains(".theme-desk .table--stock td { padding-top: 16px; padding-bottom: 16px; }");
    }

    @Test
    @DisplayName("厨房ボードの見出し（設計 現01 552:6627）：上下 20・題 32・説明 13")
    void kitchenHeadingMetrics() throws Exception {
        // 2026-09-12 に .griddle 単体から .kitchenboard 配下（app.css 33 節）へ移した。
        // 厨房ボードの値をひとところに集めるため。同じ値が 2 箇所にあると
        // 必ず片方だけ古くなる（食材・在庫の card__head で実際に踏んでいる）
        //
        // ★ 題は 28px → 32px（2026-09-13）。設計は 28px でしたが、
        //   他のページの題（.page-head__title）は 32px で、この画面だけ一段
        //   小さいままでした。店主の指示で Render にそろえる際に統一しています
        //   （RenderAlignedTypeTest）。
        String css = Files.readString(CSS);
        assertThat(css).contains(".kitchenboard .griddle .card__body { padding: 20px 24px; }");
        assertThat(css).contains(".kitchenboard .griddle h1 { font-size: 32px; line-height: 36px; }");
        assertThat(css).contains(".kitchenboard .griddle .small { font-size: 13px; color: #828282; }");

        // 移す前の定義が残っていないこと。
        // 残っていると「どちらが効いているのか」を読む人が追えなくなる
        assertThat(css).as("移動前の .griddle 単体の定義が残っている")
                .doesNotContain("\n.griddle h1 {");
    }
}
