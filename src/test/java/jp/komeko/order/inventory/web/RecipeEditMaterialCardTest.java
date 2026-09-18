package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レシピ編集の「材料」を枠で囲む（設計 ト09c 819:8679・2026-09-17、店主の指摘）。
 *
 * <p><b>何が違ったか。</b>設計では材料の表が<b>カード（枠付きの箱）</b>に入っていて、
 * 題「材料（5）」もその中にあります。実装は題が {@code .section-title} で外に出ていて、
 * 表は枠の無いまま置かれていました。
 *
 * <p>同じ画面の「その他材料費」は既に {@code <form class="card">} になっていて、
 * <b>材料だけ囲われていない</b>という不揃いでもありました。
 *
 * <p><b>なぜ枠が要るか。</b>この画面には材料の表・その他材料費・材料を足す、と
 * 3 つの塊があります。枠が無いと、どこまでが「材料の表」でどこからが次の話か、
 * 縦に読んでいって初めて分かります。囲ってあれば塊が目で拾えます。
 */
@DisplayName("レシピ編集の材料はカードに入っている")
class RecipeEditMaterialCardTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/recipe-edit.html");

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /** ★ 材料の表がカードの中にあること。 */
    @Test
    @DisplayName("★ 材料の表がカードで囲まれている")
    void theMaterialTableSitsInACard() throws Exception {
        String html = body();

        int form = html.indexOf("/quantities(id=${menuItem.id})}");
        assertThat(form).as("分量を保存するフォームが無い").isGreaterThan(0);

        // フォームの開始タグに card が付いていること
        String openTag = html.substring(html.lastIndexOf("<form", form), form);
        assertThat(openTag)
                .as("材料のフォームがカードになっていない。"
                        + "同じ画面の「その他材料費」は card なのに、材料だけ枠が無い")
                .contains("class=\"card\"");
    }

    /**
     * ★ 題はカードの中。
     *
     * <p>設計では「材料（5）」がカードの左上にあります。外に出したままだと、
     * 題と箱が離れて「何の箱か」が分かりません。
     */
    @Test
    @DisplayName("★ 材料の題はカードの中にある")
    void theHeadingIsInsideTheCard() throws Exception {
        String html = body();

        int card = html.indexOf("/quantities(id=${menuItem.id})}");
        int heading = html.indexOf("材料", card);
        assertThat(heading).as("カードの中に題が無い").isGreaterThan(card);

        // 外側に置きっぱなしの section-title が残っていないこと
        assertThat(html)
                .as("材料の題が section-title のままカードの外にある")
                .doesNotContain("<h2 class=\"section-title__text\">材料</h2>");
    }

    /** ★ 種類の件数は残す。何種類あるかは、開いた瞬間に知りたい数。 */
    @Test
    @DisplayName("★ 材料の件数（N 種類）は残っている")
    void theCountSurvives() throws Exception {
        assertThat(body())
                .as("件数が消えている")
                .contains("cost.lines().size()");
    }

    /**
     * ★ 「その他材料費」と同じ作りであること。
     *
     * <p>同じ画面で 2 つの塊の組み方が違うと、直すときにどちらに合わせるか迷います。
     */
    @Test
    @DisplayName("★ その他材料費と同じ form.card の形")
    void bothBlocksUseTheSameShape() throws Exception {
        String html = body();

        int other = html.indexOf("/other-cost(id=${menuItem.id})}");
        assertThat(other).as("その他材料費のフォームが無い").isGreaterThan(0);
        assertThat(html.substring(html.lastIndexOf("<form", other), other + 200))
                .as("その他材料費がカードでなくなっている").contains("card");
    }
}
