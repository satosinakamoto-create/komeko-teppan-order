package jp.komeko.order.web;

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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 押せる札と押せない札を、形と色で見分けられるようにする（2026-09-17、店主の指摘）。
 *
 * <p><b>何が起きていたか。</b>商品一覧の「販売」列には、字も色も形もまったく同じ
 * 「品切れ」が 2 つ並んでいました。
 *
 * <ul>
 *   <li>手で止めた品切れ …… {@code <button>}。押すと販売を再開する</li>
 *   <li>残数ゼロの品切れ …… {@code <span>}。<b>押せない</b>。数の結果なので、
 *       ここで外しても次の注文で 0 に戻る</li>
 * </ul>
 *
 * <p>app.css には「押せることは色と手の形で伝える」と書いてありましたが、
 * <b>色は押せない札と共通で、手の形（カーソル）は触るまで出ません</b>。
 * つまり伝える手段が実質ありませんでした。
 *
 * <p><b>直した形（案4）。</b>店主の案で、Shopify の「Tip」パネルと同じ考え方です。
 *
 * <pre>
 *   枠と ▸ がある  → 押せる
 *   平らで枠が無い → 読むだけ
 *
 *   緑 #0b7a1a … 売っている（押せる）
 *   赤 #d33f3f … 止めた（押せる）
 *   水色       … こちらからは動かせない案内（押せない）
 * </pre>
 *
 * <p>水色（{@code --info}）を選んだのはグレーとの違いが大きいためです。グレーは
 * 「本当は押せるはずなのに死んでいる」に見えますが、水色は「これは案内です」に見えます。
 * 残数ゼロは壊れているのではなく、ただの事実の報告なので水色のほうが正確です。
 * しかもこの水色はお知らせ帯・注記で<b>すでに使っている色</b>なので、色を増やしません。
 *
 * <p><b>この 2 つを取り違えないこと。</b>
 * <ul>
 *   <li>{@code --ok}（#3f8e6d 落ち着いた緑）…… <b>読むだけ</b>の緑。
 *       「確認済」「稼働中」「いま有効」など、他の画面にたくさんある</li>
 *   <li>{@code --action}（#0b7a1a 草緑）…… <b>押せる</b>緑</li>
 * </ul>
 * {@code .badge--gf} を丸ごと草緑にすると、押せない札まで押せそうに見えます。
 * だから草緑にするのは {@code .badge--act} と重なったときだけです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("押せる札と押せない札を見分けられる")
class PressableBadgeTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path ITEMS =
            Path.of("src/main/resources/templates/admin/items.html");
    private static final Path STOCK =
            Path.of("src/main/resources/templates/kitchen/stock.html");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** コメントを落としたテンプレート。コメントの中の文字を数えないようにする。 */
    private String template(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * セレクタ 1 件ぶんの中身（{ から } まで）を取り出す。
     *
     * <p>app.css は縦を揃えるために {@code .badge--gf      { … }} のように
     * 空白を並べている箇所があるので、空白の数は数えない。
     */
    private String rule(String css, String selector) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{")
                .matcher(css);
        assertThat(m.find()).as("%s が app.css に無い", selector).isTrue();
        return css.substring(m.start(), css.indexOf("}", m.start()));
    }

    // ------------------------------------------------------------------
    // 形（押せるものはボタンの形にする）
    // ------------------------------------------------------------------

    /**
     * ★ 押せる札は、角を落として枠と影を持つ。
     *
     * <p>錠剤のままだと、隣の押せない札と区別が付きません。
     * {@code --r-sm} は {@code .theme-desk}（机で読む画面）で 4px なので、
     * 表の中で浮かない程度に角が立ちます。
     */
    @Test
    @DisplayName("★ 押せる札はボタンの形（角・枠・影）を持つ")
    void pressableBadgesLookLikeButtons() throws Exception {
        String r = rule(css(), ".badge--act");

        assertThat(r).as("角が錠剤のまま（押せない札と同じ形）")
                .contains("border-radius: var(--r-sm)");
        assertThat(r).as("枠が無い").contains("border: 1px solid currentColor");
        assertThat(r).as("影が無い（触れる面に見えない）").contains("box-shadow");
    }

    /**
     * ★ 触らなくても押せると分かる目印。
     *
     * <p>カーソルの形は載せるまで出ませんし、タッチの端末では最後まで出ません。
     * 静止した状態で見えるものが要ります。
     */
    @Test
    @DisplayName("★ 押せる札には ▸ が付く（カーソルに頼らない）")
    void pressableBadgesCarryAStaticAffordance() throws Exception {
        String css = css();
        int at = css.indexOf(".badge--act::after");
        assertThat(at).as(".badge--act::after が無い（▸ の目印）").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("▸ を出していない").contains("content:");
    }

    // ------------------------------------------------------------------
    // 色（押せる緑と、読むだけの緑を分ける）
    // ------------------------------------------------------------------

    /**
     * ★ 押せる緑は草緑（{@code --action}）。
     *
     * <p>店主の「掲載中とかのボタンが旧色」という指摘がここで直ります。
     */
    @Test
    @DisplayName("★ 押せる緑は --action（草緑）")
    void pressableGreenUsesTheActionToken() throws Exception {
        assertThat(rule(css(), ".badge--act.badge--gf"))
                .as("押せる緑が草緑になっていない")
                .contains("color: var(--action)");
    }

    /**
     * ★ 読むだけの緑は {@code --ok} のまま。
     *
     * <p>{@code .badge--gf} は「確認済」「稼働中」「いま有効」「小麦不使用」など
     * <b>押せない札で 10 か所以上</b>使われています。ここを草緑にすると、
     * それが全部「押せそう」に見えてしまいます。
     */
    @Test
    @DisplayName("★ 読むだけの緑は --ok のまま（他の画面を巻き込まない）")
    void readOnlyGreenStaysCalm() throws Exception {
        assertThat(rule(css(), ".badge--gf"))
                .as("読むだけの緑まで草緑にしている（他画面の札が押せそうに見える）")
                .contains("color: var(--ok)");
    }

    /**
     * ★ 押せないものは平らな水色。
     *
     * <p>枠を持たせないのが肝心です。枠＝押せる、という規則をここで破ると、
     * 形で見分ける手がかりが消えます。
     */
    @Test
    @DisplayName("★ 押せない案内は平らな水色（枠を持たない）")
    void readOnlyNoticeIsFlatBlue() throws Exception {
        String r = rule(css(), ".badge--info");

        assertThat(r).as("水色の地になっていない").contains("background: var(--info-soft)");
        assertThat(r).as("水色の字になっていない").contains("color: var(--info)");
        assertThat(r).as("枠を持っている（枠＝押せる の規則が壊れる）")
                .contains("border-color: transparent");
    }

    /**
     * 水色の字が地の上で読めること（WCAG AA = 4.5:1）。
     *
     * <p>色を決め打ちで書かず、app.css の {@code .theme-snow} から実際の値を読んで測ります。
     */
    @Test
    @DisplayName("★ 水色の字は地の上で AA（4.5:1）を満たす")
    void theBlueIsReadable() throws Exception {
        String snow = css().substring(css().indexOf(".theme-snow {"));
        snow = snow.substring(0, snow.indexOf("\n}"));

        String fg = valueOf(snow, "--info:");
        String bg = valueOf(snow, "--info-soft:");

        double ratio = contrast(fg, bg);
        assertThat(ratio)
                .as("水色の字 %s が地 %s の上で読めない（%.2f:1）", fg, bg, ratio)
                .isGreaterThanOrEqualTo(4.5);
    }

    private String valueOf(String block, String token) {
        int at = block.indexOf(token);
        assertThat(at).as("%s が .theme-snow に無い", token).isGreaterThan(0);
        String v = block.substring(at + token.length(), block.indexOf(";", at)).trim();
        assertThat(v).as("%s が #rrggbb でない（この検査は 16 進だけ測れる）", token)
                .matches("#[0-9a-fA-F]{6}");
        return v;
    }

    private double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** WCAG の相対輝度。 */
    private double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    // ------------------------------------------------------------------
    // 商品一覧（文言と、押せる／押せないの割り当て）
    // ------------------------------------------------------------------

    /**
     * ★ 同じ字の札を 2 つ並べない。
     *
     * <p>「品切れ・残数」の画面はこの 2 つを「品切れ中」「残数ゼロ」と呼び分けています。
     * 商品一覧だけが両方を「品切れ」と呼んでいて、<b>2 つの画面で名前が違う</b>状態でした。
     * 向こうに合わせます。
     */
    @Test
    @DisplayName("★ 文言は「品切れ中」「残数ゼロ」（品切れ・残数の画面と同じ言葉）")
    void theTwoStatesHaveDifferentWords() throws Exception {
        String html = template(ITEMS);

        assertThat(html).as("「品切れ中」が無い").contains(">品切れ中<");
        assertThat(html).as("「残数ゼロ」が無い").contains(">残数ゼロ<");
        assertThat(html).as("紛らわしい「品切れ」がまだ残っている")
                .doesNotContain(">品切れ<");
    }

    /**
     * ★ 押せないものにボタンの形を与えない。
     *
     * <p>{@code .badge--act} が形と ▸ を付けるので、押せない残数ゼロがこれを持つと
     * 押せそうに見えます。逆に押せる札がこれを持たないと、平らなまま押せてしまいます。
     */
    @Test
    @DisplayName("★ 残数ゼロは押せない見た目、掲載中・販売中は押せる見た目")
    void shapeMatchesWhetherItIsPressable() throws Exception {
        String html = template(ITEMS);

        int zero = html.indexOf(">残数ゼロ<");
        assertThat(zero).as("残数ゼロが無い").isGreaterThan(0);
        String zeroTag = html.substring(html.lastIndexOf("<", zero), zero);
        assertThat(zeroTag).as("押せない残数ゼロにボタンの形を与えている")
                .doesNotContain("badge--act");
        assertThat(zeroTag).as("残数ゼロが平らな水色になっていない")
                .contains("badge--info");
        assertThat(zeroTag).as("押せないのに button になっている")
                .doesNotContain("<button");

        // 押せる 3 つは、いずれもボタンの形を持つ
        for (String label : new String[]{">掲載中<", ">販売中<", ">品切れ中<"}) {
            int at = html.indexOf(label);
            assertThat(at).as("%s が無い", label).isGreaterThan(0);
            String tag = html.substring(html.lastIndexOf("<button", at), at);
            assertThat(tag).as("%s がボタンの形を持っていない", label)
                    .contains("badge--act");
        }
    }

    /**
     * ★「編集中」は水色だが押せる（編集画面へ飛ぶ）。
     *
     * <p>ここが色だけの規則では説明できない唯一の例外です。だから形（枠と ▸）が要ります。
     * 水色のまま {@code .badge--act} を持たせて、押せることを形で示します。
     */
    @Test
    @DisplayName("★ 編集中は水色のままボタンの形を持つ（色だけでは決まらない）")
    void theDraftBadgeIsBlueButPressable() throws Exception {
        String html = template(ITEMS);

        int at = html.indexOf(">編集中<");
        assertThat(at).as("編集中が無い").isGreaterThan(0);
        String tag = html.substring(html.lastIndexOf("<a", at), at);

        assertThat(tag).as("編集中が水色でない").contains("badge--draft");
        assertThat(tag).as("編集中にボタンの形が無い（押せるのに平らに見える）")
                .contains("badge--act");
    }

    // ------------------------------------------------------------------
    // 品切れ・残数の画面（2 つの画面で色を揃える）
    // ------------------------------------------------------------------

    /**
     * ★ 同じ状態は、どの画面でも同じ色。
     *
     * <p>商品一覧で水色にしておいて、品切れ・残数の画面では赤のまま、では
     * 「同じ残数ゼロなのに画面によって色が変わる」ことになります。
     * あちらの状態チップはもともと全部押せないので、水色にしても矛盾しません。
     */
    @Test
    @DisplayName("★ 品切れ・残数の画面でも残数ゼロは水色")
    void theOtherScreenUsesTheSameBlue() throws Exception {
        String html = template(STOCK);

        int at = html.indexOf(">残数ゼロ<");
        assertThat(at).as("残数ゼロが無い").isGreaterThan(0);
        String tag = html.substring(html.lastIndexOf("<span", at), at);

        assertThat(tag).as("残数ゼロがまだ赤（商品一覧と色が違う）")
                .doesNotContain("var(--danger)");
        assertThat(tag).as("残数ゼロが水色になっていない")
                .contains("var(--info)");
    }

    // ------------------------------------------------------------------
    // 実際に描けること
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 商品一覧が描けて、押せる札はボタンの形で出る")
    void theListStillRenders() throws Exception {
        // 掲載中・販売中は「掲載していて書きかけでない」商品にしか出ない。
        // MenuItem の既定が visible=true / draft=false / soldOut=false なので、
        // そのまま 1 件置けば押せる札が 2 つ描かれる。
        Category c = categoryRepository.save(
                new Category("押せる札テスト" + System.nanoTime(), 999));
        menuItemRepository.save(new MenuItem(c, "押せる札テスト用お好み焼き", 1180));

        String html = mockMvc.perform(get("/admin/items"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("押せる札が 1 つも出ていない").contains("badge--act");

        // ★ ここで「品切れ」という字そのものを禁じないこと。
        //   状態のタブ「品切れ」は soldOut と outOfStock の<b>両方</b>を集める絞り込みで
        //   （AdminMenuItemController の TABS）、2 つを束ねる言葉として正しい。
        //   禁じたいのは「押せない札が、押せる札と同じ赤い見た目で出ること」なので、
        //   そこだけを見る。
        assertThat(html)
                .as("押せない札がまだ赤い（押せる「品切れ中」と同じ見た目になっている）")
                .doesNotContain("<span class=\"badge badge--stop\"");
    }
}
