package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店名（ロゴの紋と並べる「米粉と鉄板」）は明朝で出ること。
 *
 * <p><b>このテストが守っているもの＝ロゴの中の文字と、その隣の文字がそろっていること。</b>
 * 紋の中の「米粉と鉄板」は明朝です。隣がゴシックだと、同じ言葉が 2 種類の字で並びます。
 *
 * <h2>なぜトークンを分けているのか</h2>
 *
 * <p>{@code --font-serif} は使えません。どちらのテーマも潰しているからです。
 *
 * <pre>
 *   .theme-snow  { --font-serif: var(--font-sans); }
 *   .theme-night { --font-serif: var(--font-sans); }
 * </pre>
 *
 * <p>つまり画面の中で {@code font-family: var(--font-serif)} と書いても、
 * <b>解決後はゴシックです</b>。
 *
 * <p>2026-09-23 に、これを知らずに「明朝にしました」と報告しました。
 * 実測すると {@code Hiragino Kaku Gothic ProN} でした。
 * <b>トークンの名前だけを見て、解決後の値を測らなかったのが原因です。</b>
 * CSS は宣言が読めても、効いている値は別物になりえます。
 *
 * <p>そこで {@code --font-brand} を別に持ち、テーマ側で上書きしないことを固定します。
 * 上書きされた瞬間、画面は普通に表示されたまま字だけゴシックに戻ります。
 * <b>エラーは出ません。</b>だからテストで留めます。
 */
@DisplayName("店名の明朝がテーマに潰されない")
class BrandFontSurvivesTheThemeTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * コメントを落とした CSS。
     *
     * <p>このファイルには「なぜそう書くか」の説明が厚く入っていて、
     * <b>説明の中に CSS の例文がそのまま書かれています</b>。
     * 素のまま正規表現をかけると、説明用の
     * {@code .theme-snow { --font-serif: var(--font-sans); }} を
     * 本物の宣言として拾ってしまいます（2026-09-23 にこのテスト自身が踏みました）。
     *
     * <p>実装は正しいのにテストだけ落ちる、というのがいちばん時間を食う壊れ方なので、
     * 宣言を探すときは必ずこちらを使います。
     */
    private String declarationsOnly() throws Exception {
        return css().replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ --font-brand が定義されていて、中身は明朝")
    void theBrandTokenIsMincho() throws Exception {
        String css = declarationsOnly();

        int at = css.indexOf("  --font-brand:");
        assertThat(at).as("★ --font-brand が無い").isGreaterThan(0);

        String value = css.substring(at, css.indexOf(";", at));
        assertThat(value)
                .as("★ --font-brand が明朝になっていない")
                .contains("Mincho");
        assertThat(value)
                .as("★★ --font-brand が --font-sans を指している。それでは意味がない")
                .doesNotContain("--font-sans");
    }

    /**
     * ★★ テーマが {@code --font-brand} を上書きしていないこと。
     *
     * <p>{@code --font-serif} とまったく同じやり方で潰されるのを止めます。
     * 1 行足すだけで戻ってしまう種類の壊れ方で、画面は普通に出たままです。
     */
    @Test
    @DisplayName("★★ どのテーマも --font-brand を上書きしない")
    void noThemeFlattensTheBrandFont() throws Exception {
        String css = declarationsOnly();

        // テーマの宣言ブロックを取り出して、中に --font-brand が無いことを見る
        Matcher m = Pattern.compile("(?s)\\.theme-(snow|night|desk|ledger)\\s*\\{(.*?)\\n\\}")
                .matcher(css);

        int checked = 0;
        while (m.find()) {
            checked++;
            assertThat(m.group(2))
                    .as("★★ .theme-%s が --font-brand を上書きしている。"
                            + " これをやると、ロゴの中だけ明朝で隣がゴシックに戻る", m.group(1))
                    .doesNotContain("--font-brand");
        }
        assertThat(checked).as("★ テーマのブロックが 1 つも見つからない").isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 店名は --font-serif ではなく --font-brand を使う")
    void theShopNameUsesTheBrandToken() throws Exception {
        String css = declarationsOnly();

        for (String selector : new String[]{".loginpage__name {", ".theme-night .demogate__name {"}) {
            int at = css.indexOf(selector);
            assertThat(at).as("★ %s が無い", selector).isGreaterThan(0);

            String rule = css.substring(at, css.indexOf("}", at));
            assertThat(rule)
                    .as("★★ %s が --font-brand を使っていない。"
                            + " --font-serif や --font-sans では明朝にならない", selector)
                    .contains("font-family: var(--font-brand);");
        }
    }
}
