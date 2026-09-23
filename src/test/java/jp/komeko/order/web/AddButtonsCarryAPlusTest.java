package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「新しく 1 つ作る」ボタンには ＋ を付ける（2026-09-18、店主の指示）。
 *
 * <pre>
 *   「レシート登録、食材追加、レシピを取り込むの文字の前に + を付けて統一させてほしい」
 * </pre>
 *
 * <p><b>もともと半分だけ付いていました。</b>商品と卓には ＋ があり、
 * 仕入れ・食材・レシピには無い、という状態です。同じ役のボタンが
 * 画面ごとに違う見た目をしていると、探すときに毎回読むことになります。
 *
 * <pre>
 *   ＋商品を追加      admin/items.html          ← 前からあった
 *   ＋新規追加        admin/table-list.html     ← 前からあった
 *   レシートを登録    inventory/purchases.html   ← 無かった
 *   食材を追加        inventory/ingredients.html ← 無かった
 *   レシピを取り込む  inventory/recipes.html     ← 無かった
 * </pre>
 *
 * <p><b>＋ は全角・空白なし。</b>既にあった 2 つがその書き方だったので合わせます。
 * 半角 + だと、日本語の中で線が細く見えて浮きます。
 *
 * <p><b>付けないものもあります。</b>
 * <ul>
 *   <li>「カテゴリを 編集・追加」「スタッフ 編集・追加」…
 *       新しく作るだけでなく直しにも行くボタンです。
 *       ＋ は「1 つ増える」と読めるので、付けると嘘になります</li>
 *   <li>「まとめて印刷」… 何も増えません</li>
 *   <li>画面の題（{@code <h1>食材を追加</h1>} など）…
 *       ボタンではなく、いま開いている画面の名前です</li>
 *   <li>文中のリンク（「食材を追加してください」）… 文章の一部です</li>
 * </ul>
 */
@DisplayName("新しく作るボタンには ＋ が付く")
class AddButtonsCarryAPlusTest {

    private static final Path TPL = Path.of("src/main/resources/templates");

    /** 画面と、そこにある「新しく作る」ボタンの文字。 */
    private record Button(String path, String label) {}

    private static final List<Button> ADD_BUTTONS = List.of(
            new Button("admin/items.html", "商品を追加"),
            new Button("admin/table-list.html", "新規追加"),
            new Button("inventory/purchases.html", "レシートを登録"),
            new Button("inventory/ingredients.html", "食材を追加"),
            new Button("inventory/ingredients.html", "最初の食材を追加する"),
            new Button("inventory/recipes.html", "レシピを取り込む"));

    private String body(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /** ★ ボタンの文字が ＋ で始まること。 */
    @Test
    @DisplayName("★ 新しく作るボタンは ＋ で始まる")
    void everyAddButtonStartsWithAPlus() throws Exception {
        for (Button b : ADD_BUTTONS) {
            String html = body(b.path());

            assertThat(html)
                    .as(b.path() + " に「" + b.label() + "」が無い。"
                            + "文言を変えたならこのテストも直すこと")
                    .contains(b.label());

            assertThat(html)
                    .as(b.path() + " の「" + b.label() + "」に ＋ が付いていない。"
                            + "同じ役のボタンが画面ごとに違う見た目だと、"
                            + "探すときに毎回読むことになる")
                    .contains("＋" + b.label());
        }
    }

    /**
     * ★ 半角 + を使わないこと。
     *
     * <p>既にあった 2 つ（商品・卓）が全角でした。日本語の中に半角 + が混ざると
     * 線が細く見えて浮きます。
     */
    @Test
    @DisplayName("★ ＋ は全角（半角 + を混ぜない）")
    void thePlusIsFullWidth() throws Exception {
        for (Button b : ADD_BUTTONS) {
            assertThat(body(b.path()))
                    .as(b.path() + " に半角の + が付いたボタンがある")
                    .doesNotContain(">+" + b.label());
        }
    }

    /**
     * ★ 「編集・追加」には付けないこと。
     *
     * <p>新しく作るだけでなく直しにも行くボタンです。＋ は「1 つ増える」と読めるので、
     * 付けると押す前の期待と中身がずれます。
     */
    @Test
    @DisplayName("★ 編集も兼ねるボタンには ＋ を付けない")
    void editAndAddButtonsStayPlain() throws Exception {
        assertThat(body("admin/category-list.html"))
                .as("カテゴリは編集にも行くボタン。＋ だと「1 つ増える」と読める")
                .doesNotContain("＋カテゴリ");
        assertThat(body("admin/staff-list.html"))
                .as("スタッフは編集にも行くボタン")
                .doesNotContain("＋スタッフ");
    }
}
