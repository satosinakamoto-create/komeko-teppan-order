package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レシピ取り込みの確認画面のボタンを設計 ト09b（806:8314）に合わせる
 * （2026-09-18、店主の指摘「＋商品を足すボタンの色とか余白とか全然違う」）。
 *
 * <p><b>設計はボタンの種類そのものが違いました。</b>Figma を実測した値です。
 *
 * <pre>
 *   ＋ 材料を足す     #0b7a1a  14px Bold  面は白・枠なし  ＝ 文字リンク
 *   ＋ その場で作成   #d33f3f  14px Bold  面は白・枠なし  ＝ 赤い文字リンク
 *   ✓ 一致            #0b7a1a  14px Bold
 *   まとめて登録      白文字   15px Bold  面 #0b7a1a       ＝ 塗りボタン
 * </pre>
 *
 * <p>実装は「＋ 材料を足す」「＋ 商品を足す」が<b>白い枠ボタン</b>、
 * 「＋ その場で作成」が<b>緑の塗りボタン</b>でした。とくに最後のものは逆で、
 * <b>いちばん強い見た目が「まだ食材が無い」という不足の合図に付いていた</b>ことになります。
 *
 * <p><b>なぜ枠を外すか。</b>表の中に枠付きのボタンが行ごとに並ぶと、読みたい数字より
 * 枠のほうが目立ちます。レシピ編集の {@code .recbtn} のコメントにも
 * 「面まで塗ると緑の帯が縦に連なって表の数字より目立つ」と同じ趣旨が書いてあります。
 *
 * <p><b>高さは 48px を割りません。</b>設計の「＋ 材料を足す」は 21px の行ですが、
 * これは押せるものなので CLAUDE.md の「タップ領域は 48px 以上」を優先します。
 * 品切れ・残数の表やレシピの編集ボタンでも同じ判断をしています。
 */
@DisplayName("レシピ取り込みのボタンは設計 ト09b どおり")
class RecipeImportButtonsMatchFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/recipe-import.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    private String rule(String selector) throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{")
                .matcher(css);
        assertThat(m.find()).as("%s が app.css に無い", selector).isTrue();
        return css.substring(m.start(), css.indexOf("}", m.start()));
    }

    /** ★ 文字リンク型の部品があること。枠も面も持たない。 */
    @Test
    @DisplayName("★ 文字リンク型のボタン（.linkbtn）がある")
    void thereIsATextLinkButton() throws Exception {
        String r = rule(".linkbtn");

        assertThat(r).as("枠を消していない").contains("border: 0");
        assertThat(r).as("面を消していない").contains("background: none");
        assertThat(r).as("緑になっていない").contains("color: var(--action)");
        // ★ 2026-09-26：高さは --btn-h（44px）の 1 か所で決めるようにしました。
        //   ここで自前の高さを持つと、また画面ごとにズレます。
        assertThat(r).as("タップ領域が --btn-h になっていない").contains("min-height: var(--btn-h)");
    }

    /** ★ 赤い版（その場で作成）。不足の合図なので赤。 */
    @Test
    @DisplayName("★ 赤い版（.linkbtn--danger）がある")
    void thereIsARedVariant() throws Exception {
        assertThat(rule(".linkbtn--danger"))
                .as("赤になっていない").contains("color: var(--danger)");
    }

    /**
     * ★ 「材料を足す」「商品を足す」は文字リンク。
     *
     * <p>枠付きのボタン（.btn）に戻っていないことまで見ます。
     */
    @Test
    @DisplayName("★ 材料を足す・商品を足す は枠なしの文字リンク")
    void theAddButtonsAreTextLinks() throws Exception {
        String html = body();

        for (String label : new String[]{"＋ 材料を足す", "＋ 商品を足す"}) {
            int at = html.indexOf(label);
            assertThat(at).as("%s が無い", label).isGreaterThan(0);
            String tag = html.substring(html.lastIndexOf("<button", at), at);
            assertThat(tag).as("%s が文字リンクになっていない", label).contains("linkbtn");
            assertThat(tag).as("%s に枠付きボタンの class が残っている", label)
                    .doesNotContain("btn--ghost");
        }
    }

    /**
     * ★ 「その場で作成」は赤い文字リンク。
     *
     * <p>いちばん強い見た目（緑の塗り）を、不足の合図に付けないこと。
     */
    @Test
    @DisplayName("★ その場で作成 は赤い文字リンク（緑の塗りにしない）")
    void theCreateOnTheSpotIsARedLink() throws Exception {
        String html = body();

        int at = html.indexOf("＋ その場で作成");
        assertThat(at).as("その場で作成が無い").isGreaterThan(0);
        String tag = html.substring(html.lastIndexOf("<button", at), at);

        assertThat(tag).as("赤い文字リンクになっていない").contains("linkbtn--danger");
        assertThat(tag).as("緑の塗りボタンのまま").doesNotContain("btn--primary");
    }

    /** ★ まとめて登録は塗りボタンのまま。ここがこの画面の主役。 */
    @Test
    @DisplayName("★ まとめて登録は緑の塗りボタンのまま")
    void theSubmitStaysFilled() throws Exception {
        String html = body();
        int at = html.indexOf("まとめて登録");
        assertThat(at).as("まとめて登録が無い").isGreaterThan(0);
        assertThat(html.substring(html.lastIndexOf("<button", at), at))
                .as("主ボタンが塗りでなくなっている").contains("btn--primary");
    }
}
