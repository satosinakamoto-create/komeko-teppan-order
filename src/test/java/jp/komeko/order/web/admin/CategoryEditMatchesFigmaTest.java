package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * カテゴリの編集・追加を設計 ト11b（828:8811）に合わせる（2026-09-17）。
 *
 * <p>2026-09-14 の差分チェックで「要修正（実装）」に挙がっていた 3 件を片付けます。
 * どれも設計が正で、迷う余地のないものだけです（用語の統一や削除の見せ方は
 * 判断が要るので、あちらは触りません）。
 *
 * <ol>
 *   <li><b>追加ボタンを入力と同じ行へ。</b>設計は 大分類／カテゴリ名／掲載／追加する が
 *       1 本の行。実装は 3 列のグリッドの<b>下</b>にボタンがぶら下がっていました</li>
 *   <li><b>節見出しに件数。</b>設計は「登録済みのカテゴリ　4 件（掲載中 4）」。
 *       実装は題だけで、いくつあるか画面から分かりませんでした</li>
 *   <li><b>並び順の説明を画面に出す。</b>「並び順はここでは聞きません。追加した瞬間は
 *       いちばん下に入り、下の一覧で直せます」——この文はテンプレートに<b>書いてあるのに
 *       Thymeleaf のコメントの中</b>にいて、画面には出ていませんでした。
 *       追加フォームに並び順の欄が無い理由が、誰にも伝わっていなかったことになります</li>
 * </ol>
 *
 * <p>新しい CSS は足していません。{@code .row}／{@code .section-title__count} は
 * どちらも既にあるものです。
 */
@DisplayName("カテゴリの編集・追加は設計 ト11b どおり")
class CategoryEditMatchesFigmaTest {

    // ★ 2026-09-20：categories.html が 2 枚に割れました（ト11c 追加 / ト11b 編集）。
    //   どの主張がどちらの画面のものかを、ここで固定しておきます。
    private static final Path NEW_TPL =
            Path.of("src/main/resources/templates/admin/category-new.html");
    private static final Path EDIT_TPL =
            Path.of("src/main/resources/templates/admin/category-edit.html");

    /** コメントを落とした本文。「書いてあるのに出ていない」を見抜くため、これで判定する。 */
    private String body() throws Exception {
        return read(NEW_TPL);
    }

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 追加ボタンは入力と同じ行。
     *
     * <p>行の中にボタンがあることを、開始タグからボタンまでの間に
     * {@code </div>} が挟まらない…という数え方はしません（入れ子が深い）。
     * かわりに「行の開始 → 入力 → ボタン」の順で現れることを見ます。
     */
    @Test
    @DisplayName("★ 追加するボタンが入力と同じ行にある")
    void theAddButtonSitsOnTheInputRow() throws Exception {
        String html = body();

        int row = html.indexOf("class=\"row row--wrap\"");
        assertThat(row).as("追加フォームが 1 行（.row）になっていない").isGreaterThan(0);

        int input = html.indexOf("th:field=\"*{name}\"", row);
        int button = html.indexOf("追加する</button>", row);

        assertThat(input).as("行の中にカテゴリ名の入力が無い").isGreaterThan(row);
        assertThat(button).as("行の中に追加するボタンが無い").isGreaterThan(row);
        assertThat(button).as("ボタンが入力より前にある").isGreaterThan(input);

        // グリッドの下にぶら下がる元の形が残っていないこと
        assertThat(html.indexOf("</div>\n      <button class=\"btn btn--primary\" type=\"submit\">追加する"))
                .as("グリッドの下にボタンがぶら下がる元の形が残っている").isLessThan(0);
    }

    /**
     * ★ 入力とボタンの下端がそろうこと（2026-09-17、店主の指摘）。
     *
     * <p>{@code .row} は既定が {@code align-items: center} です。ラベル付きの入力は
     * 「ラベル＋入力欄」で背が高く、ボタンは 48px しかありません。中央でそろえると
     * <b>ボタンだけ上に浮きます</b>。
     *
     * <p>「入力が並んで右端にボタン」の形は カテゴリの追加・税率の改定 の 2 画面に
     * あり、どちらも同じずれ方をしていました。{@code .row__grow} を含む行だけ
     * 下ぞろえにして、まとめて直します。
     */
    @Test
    @DisplayName("★ 入力とボタンの下端がそろう（ボタンだけ浮かない）")
    void theButtonLinesUpWithTheInputs() throws Exception {
        String css = Files.readString(
                Path.of("src/main/resources/static/css/app.css")).replace("\r\n", "\n");

        int at = css.indexOf(".row:has(.row__grow)");
        assertThat(at)
                .as("入力の行を下ぞろえにする指定が無い。"
                        + ".row の既定（align-items: center）だとボタンだけ上に浮く")
                .isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("下ぞろえになっていない").contains("align-items: flex-end");
    }

    /**
     * ★ 節見出しに件数が出ること。
     *
     * <p>2026-09-20 に行き先が変わりました。もとは追加画面の下にあった
     * 「登録済みのカテゴリ　4 件」で、いまは編集画面の
     * 「このカテゴリの商品　12 品（掲載中 10 ／ 書きかけ 2）」です。
     *
     * <p><b>守っている主張は同じです</b>——節見出しに題だけ出して、
     * いくつあるか画面から分からない状態にしないこと。
     *
     * <p>★ 書きかけの数まで出すのは、削除の門と噛み合わせるためです。
     * 品数は書きかけも数えるので（{@code countByCategoryId}）、
     * 内訳が見えないと「0 品に見えるのに消せない」になります。
     */
    @Test
    @DisplayName("★ 節見出しに件数が出る（このカテゴリの商品 N 品）")
    void theSectionHeadingShowsTheCount() throws Exception {
        String html = read(EDIT_TPL);

        int at = html.indexOf("このカテゴリの商品");
        assertThat(at).as("節見出しが無い").isGreaterThan(0);

        String around = html.substring(at, Math.min(html.length(), at + 400));
        assertThat(around).as("件数の部品（.section-title__count）が無い")
                .contains("section-title__count");
        assertThat(around).as("件数が出ていない").contains("itemCount");
        assertThat(around)
                .as("書きかけの数が出ていない。品数は書きかけも数えるので、"
                        + "内訳が見えないと「0 品に見えるのに消せない」になる")
                .contains("draftCount");
    }

    /**
     * ★ 並び順を聞かない理由を画面に出す。
     *
     * <p>コメントを落とした本文で探すのが肝心です。落とさずに探すと、
     * 「テンプレートには書いてある」だけで通ってしまい、画面に出ていない今の状態を
     * 見抜けません。
     */
    @Test
    @DisplayName("★ 並び順を聞かない理由が画面に出ている（コメントの中ではない）")
    void theSortOrderExplanationIsVisible() throws Exception {
        String visible = body();
        String raw = Files.readString(NEW_TPL).replace("\r\n", "\n");

        assertThat(raw).as("そもそも説明がテンプレートに無い").contains("並び順");
        assertThat(visible)
                .as("並び順の説明がコメントの中に隠れていて、画面に出ていない")
                .contains("並び順もここでは聞きません");
    }

    /**
     * ★ 大分類を変えられない理由が、編集画面に出ていること（2026-09-20）。
     *
     * <p>欄が無いだけだと「作り忘れ」に見えます。次に触る人が善意で足し、
     * 足した瞬間に打ち間違いでメニューのタブが割れる道が戻ります。
     *
     * <p>これもコメントを落とした本文で探します。
     * 「テンプレートには書いてある」では画面に出ていないのと同じです。
     */
    @Test
    @DisplayName("★ 大分類を変えられない理由が編集画面に出ている")
    void theGroupNameIsExplainedAsFixed() throws Exception {
        String visible = read(EDIT_TPL);

        assertThat(visible)
                .as("大分類を変えられない理由が画面に出ていない。"
                        + "欄が無いだけだと作り忘れに見え、次の人が足してしまう")
                .contains("大分類はここでは変えられません");

        // ★ 実際に欄を置いていないこと（説明だけ書いて欄も置く、が起きないように）
        assertThat(visible)
                .as("★ 編集画面に大分類の入力がある。"
                        + "送ると Form の初期値 null が書かれ、タブが分裂する")
                .doesNotContain("*{groupName}")
                .doesNotContain("name=\"groupName\"");
    }
}
