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
 * 戻るボタンは見出しの左端に置く（2026-09-15、店主の判断）。
 *
 * <p><b>なぜ動かしたか。</b>戻るは「移動」、保存や追加は「実行」で、種類が違います。
 * それまで両方が見出しの右端に並んでいて、<b>分類をまとめて付ける</b>の画面では
 * {@code ← 食材・在庫へ戻る} と {@code 選んだ分類を保存する} が隣り合っていました。
 * 40 件ぶん選び終えた直後——いちばん押し間違えやすいところに、
 * 選択が全部消えるボタンが並んでいたことになります。
 * 左＝戻る／右＝実行に分けると、この事故は構造的に起きません。
 *
 * <p>※ 例に挙げた「分類をまとめて付ける」の画面は、2026-09-16 に
 * 分類ごとやめたので残っていません（{@code IngredientCategoryRemovedTest}）。
 * <b>この規則は残します。</b>戻ると破壊的な実行が隣り合う形は他の画面でも
 * 起こりうるので、位置で分けておく意味は変わらないためです。
 *
 * <p><b>寸法は店主が Figma で決めたもの（898:9098）。</b>
 * <pre>
 *   見出しの左右 padding … 40 → 0    ← 戻るボタンの左端を本文の左端にそろえる
 *   戻るボタンと題の間   … 16 → 40
 * </pre>
 * 帯の左右を 0 にしたのは、下のカードや表が本文の左端から始まるのに
 * 見出しだけ 40px 内側にあり、<b>画面に左端が 2 つある</b>状態だったためです。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("戻るボタンは見出しの左端")
class BackButtonOnTheLeftTest {

    private static final Path TPL = Path.of("src/main/resources/templates");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /**
     * 日付・月の帯（{@code .datenav}）を取り除く。
     *
     * <p><b>2026-09-18 に必要になりました。</b>店主の指示で帯を題の行へ入れたため、
     * 見出しの中に「← 前日」「← 前月」という<b>戻るではない ←</b> が現れました。
     *
     * <pre>
     *   &lt;div class="page-head"&gt;
     *     &lt;h1&gt;注文履歴&lt;/h1&gt;
     *     &lt;form class="datenav"&gt; ← 前日 … 翌日 → &lt;/form&gt;   ← これは移動ではなく切り替え
     *     …
     *   &lt;/div&gt;
     * </pre>
     *
     * <p>このテストが見張っているのは<b>「戻る」と「実行」を左右で分ける</b>ことです。
     * 帯の ← は前の日を見るための切り替えで、画面を出る操作ではありません。
     * 混ぜると、注文履歴が「戻るボタンが右端にある」と誤判定されます。
     */
    private String withoutDateBand(String block) {
        return block.replaceAll("(?s)<form[^>]*class=\"datenav\".*?</form>", "");
    }

    /** 戻るボタンを含む {@code .page-head} を、テンプレートから丸ごと集める。 */
    private List<String[]> headsWithBackButton() throws IOException {
        List<String[]> found = new ArrayList<>();
        Pattern head = Pattern.compile("(?s)<div class=\"page-head\">(.*?)</div>\\s*$",
                Pattern.MULTILINE);
        try (Stream<Path> files = Files.walk(TPL)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(p).replace("\r\n", "\n")
                        .replaceAll("(?s)<!--.*?-->", "");
                Matcher m = head.matcher(html);
                while (m.find()) {
                    String block = withoutDateBand(m.group(1));
                    if (block.contains("←") && block.contains("class=\"btn")) {
                        found.add(new String[]{TPL.relativize(p).toString().replace('\\', '/'), block});
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("★ 戻るボタンは帯のいちばん最初に置く")
    void theBackButtonComesFirst() throws Exception {
        List<String[]> heads = headsWithBackButton();
        assertThat(heads).as("戻るボタンを含む見出しが 1 つも無い").isNotEmpty();

        for (String[] h : heads) {
            String block = h[1];
            int back = block.indexOf("←");
            int title = block.indexOf("page-head__title");
            assertThat(title).as(h[0] + " に題が無い").isGreaterThan(0);
            assertThat(back)
                    .as(h[0] + " … 戻るボタンが題より後ろにある（右端に置かない）")
                    .isLessThan(title);
        }
    }

    /**
     * 右端へ飛ばす {@code .page-head__spacer} が戻るボタンより<b>後ろ</b>にあること。
     * 前にあると、戻るボタンごと右へ飛んでしまいます。
     */
    @Test
    @DisplayName("★ のばすは戻るボタンより後ろ（戻るを右へ飛ばさない）")
    void theSpacerStaysBehindTheBackButton() throws Exception {
        for (String[] h : headsWithBackButton()) {
            String block = h[1];
            int spacer = block.indexOf("page-head__spacer");
            if (spacer < 0) {
                continue;   // のばすが無い帯はそのままでよい
            }
            assertThat(block.indexOf("←"))
                    .as(h[0] + " … のばすが戻るボタンより前にある")
                    .isLessThan(spacer);
        }
    }

    @Test
    @DisplayName("★ 戻るがある帯は左右 0（左端を本文にそろえる）")
    void theBandWithABackButtonHasNoSidepadding() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(css)
                .as("戻るボタン付きの帯だけ左右を 0 にする指定が無い")
                .contains(".page-head:has(> .btn:first-child) { padding-inline: 0; }");
    }

    @Test
    @DisplayName("★ 戻るボタンと題の間は 40px（gap 16 に 24 を足す）")
    void theGapAfterTheBackButtonIsForty() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        int at = css.indexOf(".page-head > .btn:first-child {");
        assertThat(at).as("戻るボタンの右の余白の指定が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("16（gap）＋ 24 ＝ 40 になっていない")
                .contains("margin-right: 24px;");
    }
}
