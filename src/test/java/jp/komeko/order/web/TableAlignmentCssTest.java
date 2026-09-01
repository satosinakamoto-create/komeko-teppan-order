package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表の右寄せが CSS の詳細度に負けていないことを確かめる。
 *
 * <p><b>何を守っているか</b><br>
 * ユーティリティの {@code .right} は詳細度 (0,1,0)。
 * 表の土台である {@code .table th, .table td { text-align: left }} は (0,1,1) で、
 * こちらのほうが強い。そのため {@code <th class="right">} は
 * <b>13 テンプレート 37 箇所すべてで左寄せのまま</b>でした。
 * {@code <td class="right">} も、{@code .num} を併記したセルだけが右に寄っていました。
 *
 * <p>結果として「見出しは左、数字は右」で列が食い違って見えます。
 * 表の見出しが薄いグレーだった頃は気づきにくく、
 * ベタ塗りにして初めて表面化しました。
 *
 * <p><b>このテストで分からないこと</b><br>
 * 見出しと中身で<b>クラスの付け忘れ</b>があるずれ（{@code <th>} は素で
 * {@code <td class="num">} だけ右、など）はテンプレート側の問題なので、
 * ここでは捕まりません。そちらは画面を見て直すしかありません。
 * ここが守るのは「クラスを正しく書けば効く」という土台のほうです。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 */
@DisplayName("表の右寄せ（CSS の詳細度）")
class TableAlignmentCssTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("★ .right が表の中でも効く指定がある")
    void right_utility_wins_inside_tables() throws Exception {
        String css = Files.readString(APP_CSS);

        assertThat(css)
                .as(".table th.right / .table td.right の指定が無いと、"
                        + "class=\"right\" を書いても表の中では左寄せのままになります")
                .contains(".table th.right, .table td.right { text-align: right; }");
    }

    @Test
    @DisplayName("土台の左寄せより後ろに書かれている（同じ詳細度なら記述順で決まるため）")
    void the_override_comes_after_the_base_rule() throws Exception {
        String css = Files.readString(APP_CSS);

        int base = css.indexOf(".table th, .table td {");
        int override = css.indexOf(".table th.right, .table td.right");

        assertThat(base).as("土台の指定が見つからない").isGreaterThan(-1);
        assertThat(override).as("上書きの指定が見つからない").isGreaterThan(-1);
        // .table th.right は (0,2,1) で土台の (0,1,1) に勝つので順番に依存しないが、
        // 将来 .table th { text-align } 側の書き方が変わったときの保険として順番も固定する。
        assertThat(override).as("上書きは土台より後ろに置くこと").isGreaterThan(base);
    }
}
