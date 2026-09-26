package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ ボタンの高さは <b>--btn-h の 1 か所</b>でだけ決める。
 *
 * <p>2026-09-26、店主の指示「44px で全部統一して、前みたいに統一出来なかったり
 * するからコンポーネント化して実装まで持っていって」で作りました。
 *
 * <p>それまで、押せるものの高さが <b>36 / 44 / 48 / 56px</b> と 43 か所に
 * 散らばっていました。直すたびに別の画面が取り残されて、店主に 3 回
 * 同じ指摘をさせています。実測での内訳:
 *
 * <pre>
 *   見出しの帯のボタン   48px
 *   表の行のボタン       44px（品切れ・残数だけ 48px）
 *   厨房ボード           40px
 *   サイドバーのログアウト 36px
 *   大きいボタン         56px
 * </pre>
 *
 * <p>値そのものより、<b>値が何か所にあるか</b>が問題でした。1 か所なら
 * ズレようがありません。Figma の「ボタン」コンポーネント（03 部品）と対です。
 *
 * <h2>この守りが捕まえるもの</h2>
 * <ol>
 *   <li>CSS で、ボタンらしいセレクタに px の高さを直書きすること</li>
 *   <li>テンプレートで {@code style="min-height: ..."} と直書きすること
 *       ← 2026-09-26 に実際にこれで 1 時間溶かしました。インラインは
 *          どんなセレクタよりも強いので、app.css をいくら直しても動きません</li>
 * </ol>
 */
class ButtonHeightIsOneValueTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path TEMPLATES =
            Path.of("src/main/resources/templates");

    /** ボタン・押せるものを指すセレクタの目印。 */
    private static final Pattern BUTTONISH = Pattern.compile(
            "\\.btn|\\bbutton\\b|\\.recbtn|\\.kbtn|\\.kback|\\.kline__x"
                    + "|\\.linkbtn|\\.stock-toggle|\\.dragdot|\\.sb__logout");

    /** 高さの宣言。 */
    private static final Pattern HEIGHT = Pattern.compile(
            "(?<![\\w-])(min-height|height)\\s*:\\s*([^;}]+)");

    /**
     * ★ 高さを自前で持ってよい例外。増やすときは<b>理由をここに書くこと</b>。
     *
     * <p>「なんとなく大きくしたい」は理由になりません。その画面だけ
     * 押しやすくしたいなら、まず --btn-h を動かしてよいか考えること。
     */
    private static final List<String> ALLOWED = List.of(
            // ホールの人数パッド。1〜8 の数字を急いで押す場所で、
            // 指を見ずに当てられる大きさが要る。44px では小さすぎる。
            ".guest",
            // お客さま側（スマホ）は今回の統一の対象外。端末もデザインも別。
            ".theme-night"
    );

    private String css() throws IOException {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** コメントを落とす。注意書きの中の例文に一致させないため。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ --btn-h は 44px で、:root に 1 つだけある")
    void theTokenExistsOnce() throws Exception {
        String css = withoutComments(css());

        assertThat(css)
                .as("--btn-h が無い。ボタンの高さを決める値が消えている")
                .contains("--btn-h: 44px;");

        long defs = Pattern.compile("--btn-h\\s*:").matcher(css).results().count();
        assertThat(defs)
                .as("--btn-h の定義が %d か所ある。お客さま側の据え置き（.theme-night）を"
                        + "含めて 2 か所までにすること", defs)
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("★★ ボタンの高さを px で直書きしない（--btn-h に寄せる）")
    void noButtonDeclaresItsOwnPixelHeight() throws Exception {
        String css = withoutComments(css());

        // 「セレクタ { 宣言 }」に分解する。入れ子は無い（@media の中も 1 段）
        Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        List<String> bad = new ArrayList<>();

        while (rules.find()) {
            String sel = rules.group(1).trim().replaceAll("\\s+", " ");
            String decl = rules.group(2);

            if (!BUTTONISH.matcher(sel).find()) continue;
            if (ALLOWED.stream().anyMatch(sel::contains)) continue;
            // ::before / ::after は飾り。押せるものではない
            if (sel.contains("::")) continue;
            // ★ 行そのものの高さは別の話。:has(.recbtn) のように
            //   「ボタンを含む行」を指すセレクタが引っかかるので外す
            if (sel.matches(".*\\b(td|th|tr)\\s*$")) continue;

            Matcher h = HEIGHT.matcher(decl);
            while (h.find()) {
                String value = h.group(2).trim();
                if (value.contains("var(--btn-h)")) continue;
                // 0 と auto は「高さを主張しない」という意味なので通す
                if (value.equals("0") || value.equals("auto")) continue;
                bad.add(sel + "  →  " + h.group(1) + ": " + value);
            }
        }

        assertThat(bad)
                .as("ボタンの高さが px で直書きされています。var(--btn-h) に寄せてください。"
                        + "どうしても例外が要るなら ALLOWED に理由つきで足すこと:\n  "
                        + String.join("\n  ", bad))
                .isEmpty();
    }

    @Test
    @DisplayName("★★ テンプレートに style=\"height\" を直書きしない（CSS より強い）")
    void noTemplateSetsHeightInline() throws Exception {
        List<String> bad = new ArrayList<>();
        Pattern inline = Pattern.compile("style\\s*=\\s*\"([^\"]*)\"");

        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                // ★ 開発用の画面は対象外。端末の枠を px で描くのが仕事なので
                if (p.toString().replace('\\', '/').contains("/dev/")) continue;

                // ★ コメントを落としてから見ること。この書き方の注意書きを
                //   コメントに残す方針なので、落とさないと自分の注記に一致して落ちます
                //   （app.css 側で同じ罠を踏んだと CLAUDE.md にもあります）
                String html = Files.readString(p).replaceAll("(?s)<!--.*?-->", "");

                Matcher m = inline.matcher(html);
                while (m.find()) {
                    String style = m.group(1);
                    if (!HEIGHT.matcher(style).find()) continue;
                    // 画像の高さ（height:auto / max-height）は別の話。押せるものではない
                    if (style.contains("height:auto") || style.contains("height: auto")) continue;
                    if (style.contains("max-height")) continue;

                    // ★ 見るのは押せるものだけ。入力欄や画像の高さはこの守りの対象外
                    int tagStart = html.lastIndexOf('<', m.start());
                    String tag = tagStart < 0 ? "" : html.substring(tagStart, m.end());
                    if (!tag.startsWith("<button") && !tag.contains("btn")) continue;

                    bad.add(TEMPLATES.relativize(p) + "  →  " + style);
                }
            }
        }

        assertThat(bad)
                .as("テンプレートに高さが直書きされています。インラインの指定は"
                        + "どんなセレクタよりも強いので、app.css を直しても効きません。"
                        + "2026-09-26 に品切れ・残数のボタンでこれを踏みました:\n  "
                        + String.join("\n  ", bad))
                .isEmpty();
    }
}
