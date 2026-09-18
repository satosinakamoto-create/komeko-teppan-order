package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --accent} の元栓を草緑に替える（2026-09-18）。
 *
 * <p><b>このセッションで 6 回、同じ罠を踏みました。</b>
 *
 * <pre>
 *   売上の期間ボタン ／ 商品の掲載・販売 ／ レシピの編集 ／ 食材の記録する
 *   ／ 品切れのカテゴリ選択中 ／ 店舗設定のチェックと強調
 * </pre>
 *
 * <p>どれも原因は同じでした。
 *
 * <pre>
 *   :root        --accent: きつね色    お客さん側の和モダン
 *   .theme-snow  --accent: #1c1c1c     ほぼ黒
 *   .theme-desk  --accent: #0b7a78     ← <b>捨てたはずのティール</b>
 * </pre>
 *
 * <p>机で使う画面（{@code .theme-desk}）だけティールが残っていて、
 * {@code var(--accent)} を読んでいる<b>73 か所</b>が一斉にティールになります。
 * 見つけるたびに 1 か所ずつ潰していましたが、元栓が開いたままなので
 * 新しく書いた所からまた出てきます。店舗設定では 19 種類が残っていました。
 *
 * <p><b>直し方。</b>{@code .theme-desk} の {@code --accent} を {@code --action}
 * （#0b7a1a・いまの草緑）に向けます。1 行で 73 か所が直ります。
 *
 * <p><b>お客さん側は動きません。</b>{@code .theme-desk} はスタッフ側の
 * レイアウトにしか付かないので、注文画面のきつね色はそのままです。
 */
@DisplayName("--accent は捨てたティールを指さない")
class AccentIsNotTealTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** コメントを落とした CSS。注記に色の名前が出るので、必ずこちらで探すこと。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * ★ 机で使う画面の {@code --accent} は草緑を指すこと。
     *
     * <p>ここが 1 行変わるだけで、{@code var(--accent)} を読んでいる
     * 73 か所が一斉に直ります。
     */
    @Test
    @DisplayName("★ .theme-desk の --accent は --action（草緑）")
    void theDeskAccentPointsAtTheGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".theme-desk {");
        assertThat(at).as(".theme-desk の定義が無い").isGreaterThan(0);
        String block = css.substring(at, css.indexOf("\n}", at));

        assertThat(block)
                .as("--accent がまだティール（--green-700 = #0b7a78）を指している。"
                        + "var(--accent) を読む 73 か所が一斉にティールになる")
                .doesNotContain("--accent:       var(--green-700)")
                .doesNotContain("--accent: var(--green-700)");
        assertThat(block).as("--accent が --action を指していない")
                .containsPattern("--accent:\\s*var\\(--action\\)");
        assertThat(block).as("--accent-soft が --action-soft を指していない")
                .containsPattern("--accent-soft:\\s*var\\(--action-soft\\)");
    }

    /**
     * ★ ティールをベタ書きしないこと。
     *
     * <p>元栓を替えても、値を直接書けば同じことが起きます。
     * すでに書いてある所（厨房ボード・ログイン・折れ線グラフ）は既知なので、
     * <b>数が増えていないこと</b>だけを見ます。減らすのは別の作業です。
     */
    @Test
    @DisplayName("★ ティールのベタ書きが増えていない")
    void theHardCodedTealDoesNotGrow() throws Exception {
        String css = css();

        List<String> lines = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^.*#(0b7a78|ddf0ec).*$").matcher(css);
        while (m.find()) lines.add(m.group().trim());

        // 2026-09-18 時点の残り。厨房ボード・ログイン・折れ線グラフなど。
        // 減らすのは歓迎。増やすときは、なぜ --action で足りないのかをここに書くこと。
        assertThat(lines.size())
                .as("ティールのベタ書きが増えている。\n" + String.join("\n", lines))
                .isLessThanOrEqualTo(20);
    }

    /**
     * ★ 捨てた色をトークンの名前で持ち続けないこと。
     *
     * <p>{@code --green-700} という中立な名前でティールが残っていると、
     * 「緑」だと思って読んだ人がまた踏みます。名前に何色かを書いておきます。
     */
    @Test
    @DisplayName("★ --green-700 には「捨てた」と分かる注記がある")
    void theRetiredTokenIsLabelled() throws Exception {
        String raw = Files.readString(CSS).replace("\r\n", "\n");

        int at = raw.indexOf("--green-700:");
        assertThat(at).as("--green-700 が無い").isGreaterThan(0);
        String around = raw.substring(Math.max(0, at - 400), Math.min(raw.length(), at + 400));

        assertThat(around)
                .as("--green-700 が捨てた色だと分かる注記が無い。"
                        + "「緑」という名前だけ見て、また使われる")
                .containsPattern("捨て|旧|廃止|使わない");
    }
}
