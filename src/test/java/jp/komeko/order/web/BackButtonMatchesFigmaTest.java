package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戻るボタンは「白地・枠 #c7c7c7」の枠線ボタン（2026-09-15、店主の指摘）。
 *
 * <p><b>Figma の実測（ト04c 840:10123「← 食材・在庫へ戻る」）</b>
 * <pre>
 *   地 #ffffff ／ 枠 #c7c7c7 1px ／ 高さ 48 ／ 左右 24
 *   文字 15px Bold #1c1c1c
 * </pre>
 *
 * <p><b>実装はここが違っていた。</b>戻るボタンに {@code .btn--ghost} を使っていました。
 * これは<b>地も枠も透明</b>で、文字も補足の色（薄い灰）です。
 * app.css のコメントにも「{@code .btn--ghost} は枠まで透明なので、押せる範囲が見えない。
 * 『やめる』のように<b>押しても何も起きない</b>ほうが安全なボタンに使う」と書いてあります。
 *
 * <p>戻るボタンは押すと画面が変わります。何も起きないボタンではないので、
 * ここに ghost を使うのは説明と食い違っていました。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("戻るボタンは Figma どおり枠線ボタン")
class BackButtonMatchesFigmaTest {

    private static final Path TPL = Path.of("src/main/resources/templates");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** 戻るボタンらしき行（「← …戻る」「← …へ」）を全テンプレートから集める。 */
    private List<String> backButtonLines() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TPL)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String rel = TPL.relativize(p).toString().replace('\\', '/');
                int no = 0;
                for (String line : Files.readString(p).replace("\r\n", "\n").split("\n")) {
                    no++;
                    if (line.contains("class=\"btn") && line.contains("←")) {
                        found.add(rel + ":" + no + " " + line.trim());
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("★ 戻るボタンに ghost（枠まで透明）を使わない")
    void noBackButtonUsesTheGhostStyle() throws Exception {
        List<String> ghosts = backButtonLines().stream()
                .filter(l -> l.contains("btn--ghost"))
                .toList();
        assertThat(ghosts)
                .as("押せる範囲が見えない戻るボタンが残っている")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 戻るボタンは 1 つ以上ある（消してしまっていないこと）")
    void theBackButtonsStillExist() throws Exception {
        assertThat(backButtonLines()).as("戻るボタンが 1 つも無い").isNotEmpty();
    }

    /**
     * 素の {@code .btn} の地。Figma の枠線ボタンは白です。
     * {@code var(--surface)} のままだと {@code .theme-snow} の {@code #f7f9fb}（うすい灰）で、
     * カードを白に直した（{@link CardSurfaceMatchesFigmaTest}）あとは
     * ボタンだけ灰色が残って浮きます。
     */
    @Test
    @DisplayName("★ 枠線ボタンの地は白・枠は #e8e8e8")
    void theOutlinedButtonIsWhite() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        int at = css.indexOf(".theme-desk .btn {");
        assertThat(at).as(".theme-desk .btn が app.css に無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("--btn-bg: #ffffff;");
        // ★ 2026-09-22：#c7c7c7 → #e8e8e8。
        //   9 月の画面監査で 6 グループから同じ指摘（設計の stroke は #e8e8e8）。
        //   店主の判断「大体はフィグマ通りに作って欲しい」で設計に寄せました。
        assertThat(rule).contains("--btn-bd: #e8e8e8;");
    }
}
