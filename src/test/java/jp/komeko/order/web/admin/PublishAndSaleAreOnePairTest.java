package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品を追加・編集する画面で、「掲載」と「販売」が 1 組に見えること
 * （2026-09-19、設計 ト10b 745:7822）。
 *
 * <p>店主の指摘「販売と掲載の間に余計な余白が合ったから消した」。実測:
 *
 * <pre>
 *   直す前   .field の下マージン 16 ＋ .formcard の gap 28 ＝ 44px
 *   設計     あき 24 ＝ 24px
 *   直した後 24px（塊の高さ 134.8 ＋ 24 ＋ 134.8 ＝ 293.5 で確認）
 * </pre>
 *
 * <p><b>3 幅とも同じ値でした</b>（1432 / 1024 / 1920 のどれでも 44px）。
 * この画面に幅ごとの上書きは無いので、1 か所直せば 3 つとも直ります。
 *
 * <h2>なぜ .formcard の gap を動かさないか</h2>
 * <p>あの 28px は商品名・価格・写真のあいだにも効いています。
 * ここだけのために動かすと、関係のない 4 か所が一緒に動きます。
 * <b>2 つを包んで、中の間隔だけ変える</b>のが狭い直し方です。
 *
 * <p>掲載と販売は「この品をいまどう扱うか」という同じ問いの前半・後半なので、
 * 近づけるほど 1 組として読めます。設計が 24px にしているのもそういうことだと読みました。
 *
 * <p><b>この画面は 2026-09-19 まで {@code target/allscreens} に撮っていませんでした。</b>
 * 撮っていないものは測れないので、{@code AllScreensDumpTest} に
 * {@code s05b-item-new} を足してあります。
 */
@DisplayName("掲載と販売は 1 組")
class PublishAndSaleAreOnePairTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/item-form.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path DUMP =
            Path.of("src/test/java/jp/komeko/order/inventory/AllScreensDumpTest.java");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\\s+", "");
    }

    /**
     * ★ 掲載と販売が同じ包みの中にいること。
     *
     * <p>包みを外すと {@code .formcard} の gap 28 ＋ {@code .field} の 16 に戻り、
     * また 44px になります。
     */
    @Test
    @DisplayName("★ 掲載と販売は同じ包みの中にいる")
    void bothLiveInTheSameWrapper() throws Exception {
        String html = tpl();

        int wrap = html.indexOf("class=\"statefields\"");
        int publish = html.indexOf(">掲載<");
        int sale = html.indexOf(">販売<");

        assertThat(wrap).as("statefields の包みが無い").isGreaterThan(0);
        assertThat(publish).as("掲載が無い").isGreaterThan(0);
        assertThat(sale).as("販売が無い").isGreaterThan(0);

        assertThat(wrap).as("包みが掲載より後ろにある").isLessThan(publish);
        assertThat(publish).as("掲載と販売の順番が逆").isLessThan(sale);

        // 2 つのあいだで包みが閉じていないこと＝同じ包みの中にいる
        assertThat(html.substring(publish, sale))
                .as("掲載と販売のあいだで包みが閉じている。別々の塊になり 44px に戻る")
                .doesNotContain("</div>\n\n      </div>");
    }

    /** ★ 中の間隔は設計どおり 24px。 */
    @Test
    @DisplayName("★ 掲載と販売のあいだは 24px（設計 ト10b「あき 24」）")
    void theGapIsTwentyFour() throws Exception {
        String css = css();

        assertThat(css).as(".statefields が無い").contains(".statefields{");
        assertThat(css).as("縦に積んでいない").contains("flex-direction:column");
        assertThat(css).as("あいだが設計の 24px でない").contains("gap:24px");

        // 包んだままだと .field の下マージン 16 が中で効いて 24 + 16 になる
        assertThat(css)
                .as(".statefields の中の .field の下マージンを消していない。"
                        + "24 + 16 = 40px になる")
                .contains(".statefields.field{margin-bottom:0;}");
    }

    /**
     * ★ {@code .formcard} の gap は 28px のまま。
     *
     * <p>ここを動かすと、商品名・価格・写真のあいだも一緒に動きます。
     */
    @Test
    @DisplayName("★ ほかの段（28px）は動かしていない")
    void theOtherGapsAreUntouched() throws Exception {
        String css = css();
        int at = css.indexOf(".formcard{");
        assertThat(at).as(".formcard が無い").isGreaterThan(0);
        assertThat(css.substring(at, css.indexOf('}', at)))
                .as(".formcard の gap が 28px でなくなっている。"
                        + "商品名・価格・写真のあいだも一緒に動く")
                .contains("gap:28px");
    }

    /**
     * ★ この画面を撮り続けること。
     *
     * <p>撮っていないものは測れません。2026-09-19 まで撮っておらず、
     * 余白を実測できませんでした。
     */
    @Test
    @DisplayName("★ 商品を追加する画面を allscreens に撮っている")
    void theScreenIsCaptured() throws Exception {
        assertThat(Files.readString(DUMP))
                .as("商品を追加する画面を撮っていない。撮っていないものは測れない")
                .contains("s05b-item-new");
    }
}
