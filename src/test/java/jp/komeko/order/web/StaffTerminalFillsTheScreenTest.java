package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店舗端末の 3 画面は、画面いっぱいまで暗いままであること。
 *
 * <p><b>このテストが守っているもの＝薄暗い店内で白い板を手渡さないこと。</b>
 *
 * <p>番号の盤面・人数・番号の移動の 3 画面は、
 * {@code <body class="theme-snow">}（明るい）の中に
 * {@code <main class="theme-night staff-order">}（暗い）を置いた作りです。
 * 暗いのは {@code main} の高さぶんだけなので、
 * <b>中身より画面が高いと、その下に body の白が出ます。</b>
 *
 * <p>2026-09-23 に 1372×772 で撮って見つけました。「人数」の画面は
 * 中身が 430px しかなく、<b>下の 44% が白い板</b>になっていました。
 * 店舗端末はタブレットで縦が 768〜1024px あるため、実機では必ず出ます。
 *
 * <p><b>見た目では気づけません。</b>開発中はブラウザの窓が小さければ埋まって見え、
 * スクロールしないページなので、高さのある実機でしか現れません。
 *
 * <p>直し方は {@code min-height: 100vh} の 1 行です。
 * 逆に言えば、1 行消えるだけで白い板が戻ります。
 */
@DisplayName("店舗端末の画面は下まで暗い")
class StaffTerminalFillsTheScreenTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates/staff");

    @Test
    @DisplayName("★★ .staff-order は画面の高さまで伸びる")
    void theDarkAreaReachesTheBottom() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        int at = css.indexOf(".staff-order { padding-block: 40px;");
        assertThat(at).as("★ .staff-order の規則が見つからない").isGreaterThan(0);

        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule)
                .as("★★ 高さの床が無い。中身が短い画面で、下に body の白が出る")
                .contains("min-height: 100vh");
    }

    /**
     * ★ 明るい body の中に暗い main を置く形そのものは変えていません。
     *
     * <p>この 3 画面だけ暗いのは意図で、スタッフが薄暗い店内で持つ端末だからです。
     * ただし<b>その前提が「暗い側が画面を覆いきる」こと</b>なので、
     * 組み合わせが残っている限り上のテストが要ります。
     * 逆に body ごと暗くしたなら、この形は消えるはずです。
     */
    @Test
    @DisplayName("★ 明るい body ＋ 暗い main の組み合わせが残っているか確かめる")
    void theLightBodyDarkMainPairStillExists() throws Exception {
        List<Path> files = List.of(
                TEMPLATES.resolve("order-board.html"),
                TEMPLATES.resolve("order-guests.html"),
                TEMPLATES.resolve("order-switch.html"));

        int paired = 0;
        for (Path p : files) {
            if (!Files.exists(p)) {
                continue;
            }
            if (Files.readString(p).contains("theme-night staff-order")) {
                paired++;
            }
        }

        assertThat(paired)
                .as("★ 3 画面とも形が変わっている。body ごと暗くしたなら、"
                        + "上の min-height テストは役目を終えているので、"
                        + "このクラスごと見直すこと")
                .isGreaterThan(0);
    }
}
