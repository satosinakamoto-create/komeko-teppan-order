package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「食材の分類をまとめて付ける」を Figma「ト04d」841:9999 に合わせる（2026-09-14）。
 *
 * <p><b>いちばん大きい差は、表に並ぶ行の範囲でした。</b>
 * 実装は<b>未分類だけ</b>を並べていましたが、Figma はキャベツ＝野菜、豚バラ＝肉…と
 * <b>分類済みの食材も、いまの分類が選ばれた状態で</b>並んでいて、
 * 未分類の行にだけ右へ「未分類」と添えてあります。
 *
 * <p>この違いは見た目の話ではありません。未分類だけを出すと、
 * <b>付け間違いを直せない</b>——「大葉を野菜にしたつもりが調味料になっていた」と
 * 気づいても、大葉はもう未分類ではないのでこの画面から消えています。
 * 1 件ずつ詳細を開いて直すしかない。まとめて付ける画面を作った意味が半分無くなります。
 *
 * <p><b>保存ボタンの位置も違いました。</b>Figma は見出しの帯の中（右上）。
 * 実装は表の下です。食材が 40 件あると、決め終わったときにボタンは画面の外にあります。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("分類をまとめて付けるは Figma ト04d どおり")
class IngredientCategorizeMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/ingredient-categorize.html");
    private static final Path CTL =
            Path.of("src/main/java/jp/komeko/order/inventory/web/InventoryIngredientController.java");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    private String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- 行の範囲

    @Test
    @DisplayName("★ 分類済みの食材も並べる（付け間違いを直せるように）")
    void everyIngredientGetsARow() throws Exception {
        String m = withoutComments(read(TPL));

        // 表が回すのは「未分類だけ」ではなく全件
        assertThat(m)
                .as("表がまだ未分類だけを回している")
                .doesNotContain("th:each=\"i : ${unclassified}\"");
        assertThat(m).as("全件を回していない").contains("${ingredients}");

        // いまの分類が選ばれた状態で出す。選び直しに来た人の手が止まる
        assertThat(m).as("いまの分類を選択済みにしていない").contains("i.category");
    }

    @Test
    @DisplayName("★ 未分類の行にだけ「未分類」と添える（Figma の大葉の行）")
    void theUnclassifiedRowsAreMarked() throws Exception {
        String m = withoutComments(read(TPL));
        assertThat(m).contains("未分類");
        assertThat(m)
                .as("未分類の印が条件付きになっていない")
                .contains("${i.category == null}");
    }

    /** 画面が全件を並べるなら、コントローラも全件を渡していなければ意味がない。 */
    @Test
    @DisplayName("★ コントローラが全件を渡す")
    void theControllerHandsOverEveryIngredient() throws Exception {
        String java = read(CTL);
        // GET 側は categorizeForm。categorize( は保存の POST なので取り違えないこと
        int at = java.indexOf("public String categorizeForm(");
        assertThat(at).as("categorizeForm() が無い").isGreaterThan(0);
        String body = java.substring(at, java.indexOf("\n    }", at));
        assertThat(body).as("ingredients をモデルに載せていない").contains("\"ingredients\"");
    }

    // ---------------------------------------------------------------- 形

    @Test
    @DisplayName("★ 保存ボタンは見出しの帯の中（表の下だと 40 件目で画面の外）")
    void theSaveButtonSitsInTheBand() throws Exception {
        String m = withoutComments(read(TPL));

        int head = m.indexOf("class=\"page-head\"");
        assertThat(head).as("見出し帯が無い").isGreaterThanOrEqualTo(0);
        int table = m.indexOf("<table");
        assertThat(table).as("表が無い").isGreaterThan(0);

        int save = m.indexOf("選んだ分類を保存する");
        assertThat(save).as("保存ボタンが無い").isGreaterThan(0);
        assertThat(save).as("保存ボタンが表より後ろにある（Figma は見出しの中）").isLessThan(table);
    }

    /**
     * 戻り口が 2 つ（帯の上の小さいリンクと、帯の中のボタン）あって、
     * 同じ場所へ行く入口が並んでいました。Figma は帯のボタンだけです。
     */
    @Test
    @DisplayName("★ 戻り口は帯のボタン 1 つだけ")
    void thereIsOnlyOneWayBack() throws Exception {
        String m = withoutComments(read(TPL));
        assertThat(m)
                .as("帯の外にもう 1 つ戻るリンクが残っている")
                .doesNotContain("<p class=\"small\"><a th:href=\"@{/inventory/ingredients}\">");
    }
}
