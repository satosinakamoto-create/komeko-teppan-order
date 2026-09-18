package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食材の行から出る道を 1 本にする（2026-09-18、店主の判断）。
 *
 * <p><b>何が起きていたか。</b>食材・在庫の 1 行から、同じ食材について同じ操作に行ける道が
 * 2 本出ていました。
 *
 * <pre>
 *   食材名を押す   → /inventory/ingredients/{id}            詳細（内訳・棚卸し・廃棄・履歴・設定）
 *   記録するを押す → /inventory/ingredients/record?ingredient={id}   記録（棚卸し・廃棄）
 * </pre>
 *
 * <p>記録画面は食材を選んだ状態で開くので、<b>行から見ると同じ食材の同じ操作</b>でした。
 * 店主も「記録するボタンごとに分かれているのだから、キャベツならキャベツ専用に
 * なっていると思っていた」と言っています。実際そうなっているのに、画面に
 * 食材の選択欄が残っているせいで専用に見えていませんでした。
 *
 * <p><b>直した形。</b>行の「記録する」を詳細画面へ向けます。詳細画面は既に
 * その食材専用で、棚卸し・廃棄のフォームも持っています。
 *
 * <p><b>一括のほうは帯に独立させます。</b>食材を選び直しながら続けて入力する使い方は
 * 残す価値があります（コントローラのコメントにも「仕込み後は何品も続けて記録する」と
 * あります）。ただし<b>それは行から入るものではない</b>ので、一覧の帯に
 * 「まとめて棚卸し」を置きます。
 *
 * <p>結果として、行からは 1 本・帯からは一括、と役割で分かれます。
 */
@DisplayName("食材の行から出る道は 1 本")
class IngredientRowHasOneWayInTest {

    private static final Path LIST =
            Path.of("src/main/resources/templates/inventory/ingredients.html");

    private String body() throws Exception {
        return Files.readString(LIST).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 行の「記録する」は詳細画面へ。
     *
     * <p>食材名のリンクと同じ行き先になり、行からの道が 1 本になります。
     */
    @Test
    @DisplayName("★ 行の記録するは、その食材の画面へ行く")
    void theRowButtonGoesToTheIngredientScreen() throws Exception {
        String html = body();

        int at = html.indexOf(">記録する</a>");
        assertThat(at).as("記録するボタンが無い").isGreaterThan(0);
        String tag = html.substring(html.lastIndexOf("<a ", at), at);

        assertThat(tag)
                .as("行の記録するが、まだ食材を選ぶ記録画面へ行っている。"
                        + "行から同じ食材の同じ操作に道が 2 本あることになる")
                .doesNotContain("/record(ingredient=");
        assertThat(tag).as("その食材の画面へ向いていない")
                .contains("/inventory/ingredients/{id}");
    }

    /**
     * ★ 一括のほうは帯に置く。
     *
     * <p>食材を選び直しながら続けて入力する使い方は残します。行から入るものではないので、
     * 一覧の帯からにします。
     */
    @Test
    @DisplayName("★ まとめて棚卸しの入口が帯にある")
    void theBatchEntryIsInTheHeader() throws Exception {
        String html = body();

        int head = html.indexOf("class=\"section-title inv-ingredients\"");
        assertThat(head).as("見出しの帯が無い").isGreaterThan(0);
        String band = html.substring(head, html.indexOf("</div>", html.indexOf("食材を追加", head)));

        assertThat(band).as("帯に一括の入口が無い").contains("まとめて棚卸し");
        assertThat(band).as("一括の入口が記録画面を指していない")
                .contains("/inventory/ingredients/record");
    }

    /** ★ 食材名のリンクは残す。押せる場所が減ると、初めての人が探すことになる。 */
    @Test
    @DisplayName("★ 食材名のリンクは残っている")
    void theNameStaysClickable() throws Exception {
        assertThat(body())
                .as("食材名が押せなくなっている")
                .contains("@{/inventory/ingredients/{id}(id=${l.ingredient().id})}");
    }
}
