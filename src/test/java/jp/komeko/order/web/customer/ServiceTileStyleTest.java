package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * サービスのタイル（設計 暗03）の見た目を固定する。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 * ここで見ているのは CSS の指定そのものなので、画面を描く必要がありません。
 *
 * <p><b>何を守っているか</b><br>
 * 2026-09-06 に、設計と 3 つずれているのが見つかりました。
 * どれも例外を出さず、画面も壊れません。<b>ただ違う色・違う大きさで出るだけ</b>なので、
 * 設計と並べて見比べないと気づけません。
 *
 * <ul>
 *   <li><b>タイルの地が 1 段明るかった</b>（{@code --surface} #232323 →
 *       設計は {@code --bg} #141414）。本文が #202020 なので、
 *       設計では<b>タイルのほうが暗い</b>のに、実装では明るかった。
 *       押せるところが浮いて見えるか窪んで見えるかが逆になります。</li>
 *   <li><b>札の太さが 400 だった</b>（設計は 500）。{@code font: inherit} で
 *       本文の太さをそのまま引き継いでいたためです。</li>
 *   <li><b>「スタッフを呼ぶ」がタイルからはみ出していた</b>（実測 6px）。
 *       設計はここだけ 14px Bold にして 1 行に収めています。</li>
 * </ul>
 */
@DisplayName("サービスのタイル（暗03）")
class ServiceTileStyleTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path SERVICE_HTML =
            Path.of("src/main/resources/templates/customer/service.html");

    /** {@code .svc-tile} の指定だけを切り出す。 */
    private String tileRule() throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(".svc-tile {");
        assertThat(at).as(".svc-tile が無い").isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at));
    }

    @Test
    @DisplayName("★ タイルの地は、本文より暗い（設計は #141414 ＝ --bg）")
    void tileIsDarkerThanTheBody() throws Exception {
        // 本文は --bg-elevated（#202020）。タイルはそれより暗い --bg（#141414）。
        // --surface（#232323）に戻すと、明暗が設計と反対になる
        assertThat(tileRule()).as("タイルの地が設計と違う").contains("background: var(--bg);");

        // hex を直接書かないこと。テーマの色を変えたときにここだけ取り残される。
        // ★ コメントは外してから見る。経緯として #232323 などを書いてあるので、
        //   そのまま探すと説明文に反応する
        String declarations = tileRule().replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(declarations).as("色を直書きしている").doesNotContain("#");
    }

    @Test
    @DisplayName("★ 札は 16px の Medium（font: inherit のあとに書く）")
    void labelIsMediumWeight() throws Exception {
        String rule = tileRule();
        assertThat(rule).contains("font-size: 16px;");
        assertThat(rule).as("太さが本文のまま（400）になる").contains("font-weight: 500;");

        // font: inherit は太さも戻すので、順番が逆だと効かない
        assertThat(rule.indexOf("font: inherit"))
                .as("font: inherit が font-weight より後にある")
                .isLessThan(rule.indexOf("font-weight: 500"));
    }

    @Test
    @DisplayName("★ button の既定の左右余白を消してある（消さないと札が 2 行になる）")
    void clearsTheButtonPadding() throws Exception {
        // button は既定で左右 6px を持つ。残すと 106px のタイルの中身が 94px になり、
        // 98px の「スタッフを呼ぶ」が入らずに折り返す。
        // border: 0 では消えない（padding は別の指定）
        assertThat(tileRule()).as("button の余白が残り、札が折り返す").contains("padding: 0;");
    }

    @Test
    @DisplayName("★ 「スタッフを呼ぶ」だけ 14px Bold（設計 暗03）")
    void staffCallLabelIsSmaller() throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(".svc-tile--call {");
        assertThat(at).as(".svc-tile--call が無い").isGreaterThan(-1);
        String rule = css.substring(at, css.indexOf('}', at));

        assertThat(rule).contains("font-size: 14px;");
        assertThat(rule).contains("font-weight: 700;");

        // 画面側で付いていないと意味がない
        assertThat(Files.readString(SERVICE_HTML))
                .as("スタッフを呼ぶのタイルに付いていない")
                .contains("svc-tile svc-tile--call");
    }

    @Test
    @DisplayName("★ 同じ名前の古い指定が下に残っていない（あとから読まれて上書きされる）")
    void hasNoStaleDuplicateRule() throws Exception {
        String css = Files.readString(APP_CSS);

        // かつて別の目的の .svc-tile--call がファイルの下にあった。
        // 下にあるぶんあとから読まれるので、地の色を上書きして
        // 1 枚だけ #202020 で浮くことになる
        assertThat(css.split("\\.svc-tile--call \\{", -1).length - 1)
                .as(".svc-tile--call が 2 つ以上ある")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("長い品名は折り返す。ただし収まる札まで折らない")
    void longNamesWrapButShortOnesDoNot() throws Exception {
        String rule = tileRule();

        // サービスの品名は店が管理画面から変えられる。
        // nowrap のままだと長い名前がタイルからはみ出す
        assertThat(rule).doesNotContain("white-space: nowrap");

        // ★ anywhere は「最小幅の計算」まで変えるので、収まる札まで折り返す。
        //   実際それで「スタッフを呼ぶ」（98px・タイル 106px）が 2 行になった
        assertThat(rule).as("収まる札まで折り返す指定になっている")
                .doesNotContain("overflow-wrap: anywhere");
        assertThat(rule).contains("overflow-wrap: break-word;");
    }
}
