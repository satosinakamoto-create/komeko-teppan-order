package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一覧の名前はただの文字にする（2026-09-20、店主の指摘）。
 *
 * <p>店主の言葉：「卓名とかカテゴリの名前から編集出来るのはなぜ？
 * 編集ボタンあるんだからそっちから行けるようにして名前は黒文字でイイじゃん」
 *
 * <p><b>設計もそうなっています。</b>Figma の 1 列目の文字を実測した値です。
 * <pre>
 *   ト11 カテゴリ  「広島風お好み焼き」 16px/Regular 色 #272727
 *   ト12 卓       「カウンター札1」    16px/Regular 色 #272727
 *   ト15 スタッフ  「店長」            16px/Regular 色 #272727
 *   ト10 商品     「肉玉米粉そば」     16px/Regular 色 #272727
 * </pre>
 * どれも黒の Regular で、リンクではありません。
 * 実装は 4 画面とも <b>緑 #0b7a1a の下線リンク</b>でした。
 *
 * <p><b>なぜ直すのがよいか。</b>
 * 緑は「押せる」の合図に使うと決めた色です。名前まで緑にすると、
 * 1 つの行に「押せるもの」が名前と編集ボタンの 2 系統できて、
 * <b>どちらから直すのが正しいのか分からなくなります</b>。入口は 1 本にします。
 *
 * <p><b>商品（items.html）だけは外してあります。</b>
 * あの画面には編集の入口が名前しかありません——帯は「＋商品を追加」だけ、
 * 行にもボタンがありません。名前を黒くすると<b>商品を直す手段が消えます</b>。
 * 設計（ト10）にも行のボタンが無く、設計側に入口が足りていない状態です。
 * 入口をどう足すか決まってから直します。
 */
@DisplayName("一覧の名前はリンクにしない")
class NamesAreNotLinksTest {

    private static final Path TPL = Path.of("src/main/resources/templates/admin");

    /** コメントを落とした本文で見る。注意書きの中の a タグに引っかからないため。 */
    private String tpl(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 名前のセルにリンクを置かない。
     *
     * <p>セルの中だけを切り出して見ます。画面のどこかに {@code <a>} があるのは
     * 当たり前（編集ボタンもリンク）なので、ファイル全体で探すと意味がありません。
     */
    private void assertNameCellIsPlain(String file, String marker, String sample) throws Exception {
        String html = tpl(file);
        int at = html.indexOf(marker);
        assertThat(at).as(file + " に名前のセルが無い（目印: " + marker + "）").isGreaterThan(0);

        String cell = html.substring(at, html.indexOf("</td>", at));
        assertThat(cell)
                .as("★ " + file + " の名前がリンクのまま。"
                        + "設計は黒の Regular で、入口は編集ボタン 1 本にする")
                .doesNotContain("<a ");
        assertThat(cell).as(file + " に名前が出ていない").contains(sample);
    }

    @Test
    @DisplayName("★ カテゴリの名前はただの文字（編集は行の「編集する」から）")
    void categoryNameIsPlain() throws Exception {
        assertNameCellIsPlain("category-list.html", "class=\"wrap col-name\"", "${c.name}");
    }

    @Test
    @DisplayName("★ 卓名はただの文字（編集は帯の［編集］から）")
    void tableNameIsPlain() throws Exception {
        String html = tpl("table-list.html");
        int at = html.indexOf("${t.name}");
        assertThat(at).as("卓名が無い").isGreaterThan(0);
        String cell = html.substring(html.lastIndexOf("<td", at), html.indexOf("</td>", at));
        assertThat(cell).as("★ 卓名がリンクのまま").doesNotContain("<a ");

        // 入口が消えていないこと。黒くするのは、代わりの入口があるときだけ
        assertThat(html).as("帯に編集の入口が無い。名前を黒くすると直す手段が消える")
                .contains("/admin/tables/edit");
    }

    @Test
    @DisplayName("★ スタッフ名はただの文字（編集は帯の［編集］から）")
    void staffNameIsPlain() throws Exception {
        String html = tpl("staff-list.html");
        int at = html.indexOf("${s.displayName}");
        assertThat(at).as("スタッフ名が無い").isGreaterThan(0);
        String cell = html.substring(html.lastIndexOf("<td", at), html.indexOf("</td>", at));
        assertThat(cell).as("★ スタッフ名がリンクのまま").doesNotContain("<a ");

        assertThat(html).as("帯に編集の入口が無い").contains("/admin/staff/edit");
    }

    /**
     * ★ 名前を黒くするなら、代わりの入口が要る。
     *
     * <p>商品はまだ名前がリンクです。それでよい、ではなく
     * <b>「入口が名前しかないので、まだ外せない」</b>という状態を書き残しておきます。
     * 入口（行の「編集する」か、帯の［編集］）を足したら、ここも外してください。
     */
    @Test
    @DisplayName("商品はまだ名前がリンク（入口が他に無いため。入口を足したら外す）")
    void itemNameIsStillALinkUntilThereIsAnotherWayIn() throws Exception {
        String html = tpl("items.html");

        boolean nameIsLink = html.contains("@{/admin/items/{id}/edit(id=${item.id})}\" th:text=\"${item.name}");
        boolean hasRowEditButton = html.contains("class=\"btn btn--sm\"") && html.contains("編集する");
        boolean hasHeadEditButton = html.contains("btn--edit");

        assertThat(nameIsLink || hasRowEditButton || hasHeadEditButton)
                .as("★ 商品を直す入口が 1 つも無い。名前のリンクを外すなら、"
                        + "先に行の「編集する」か帯の［編集］を足すこと")
                .isTrue();
    }
}
