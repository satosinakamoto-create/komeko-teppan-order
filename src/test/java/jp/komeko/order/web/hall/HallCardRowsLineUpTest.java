package jp.komeko.order.web.hall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ホール・会計の卓カードで、段が横に揃っていること（2026-09-19、店主の指摘）。
 *
 * <p>店主の言葉は「ホール会計の line がズレてるのがやはり気になる、
 * これは何が原因だっけ？ボタンの高さ？」でした。<b>ボタンではありません。</b>
 * ボタンはどのカードも 44px でした。
 *
 * <h2>測って分かったこと</h2>
 * <p>見出しの行は全画面 82px で完全に一致していて、そこは原因ではありませんでした。
 * レーン（在卓・お会計待ち・バッシング）の上端・見出し・中身の始まりも
 * ズレ 0px でした。<b>ズレていたのはカードの中です。</b>
 *
 * <pre>
 *                        直す前                    直したあと
 *   卓名の行（札あり）    79.2px（札が 2 行目に落ちる）  36px
 *   卓名の行（札なし）    33.6px                        36px
 *   金額の行             39.2px または 80px             39.2px
 *   ボタンの位置          187.4 / 230.2 / 271.4 …       187.4（在卓 6 枚すべて）
 * </pre>
 *
 * <h2>原因は 3 つ。どれも設計と実装で値がちがっていた</h2>
 * <ol>
 *   <li><b>列の左右の余白が 24px（設計は 16px）。</b>
 *       8px×2 を余分に食ってカードが 15.3px 細くなり、
 *       卓名の横に札が入らず落ちていた。落ちると下の行が全部 45.6px 下がる。
 *       <pre>
 *         設計 列 360 → 本文 328 → 頭 280   卓名 135 + 8 + 札 102 = 245（あまり 35）
 *         実装 列 363 → 本文 315 → 頭 265   卓名 158 + 8 + 札 108 = 274（★ -9.3）
 *       </pre></li>
 *   <li><b>札が 38px（設計は 36px）。</b>素の {@code .badge} が
 *       {@code border: 1px solid transparent} を持っていて、透明でも太さは残る。
 *       札のあるカードと無いカードで頭が 4px ちがい、下が全部 4px ずれていた。</li>
 *   <li><b>金額の行が折り返していた。</b>注記が長いカードだけ
 *       39.2px → 80px に伸び、そのカードのボタンだけ 42.8px 下がっていた。</li>
 * </ol>
 *
 * <p><b>このテストはスクリーンショットでは絶対に見つからない差を守っています。</b>
 * 20px と 24px、36px と 38px の違いは目で見て分かりません。
 * 測ると一度で出ます（CLAUDE.md「Figma から実装するときは実数値で測る」）。
 */
@DisplayName("ホールの卓カードは段が揃う")
class HallCardRowsLineUpTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /**
     * コメントを外してから探す。
     *
     * <p>このプロジェクトの CSS は「なぜそう書くか」を厚く書く決まりなので、
     * 説明文のほうに検索語が入っていて、テストが素通りすることが何度もありました。
     */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** 宣言ブロックを 1 つ取り出す。無ければ空文字。 */
    private String block(String selector) throws Exception {
        String css = css();
        int at = css.indexOf(selector);
        if (at < 0) return "";
        int open = css.indexOf('{', at);
        int close = css.indexOf('}', open);
        if (open < 0 || close < 0) return "";
        return css.substring(open + 1, close);
    }

    /**
     * ★ 列の中身の左右の余白は 16px（設計 ト02「Frame 11」pad 24/16/24/16）。
     *
     * <p>ここが 24px だとカードが 15.3px 細くなり、卓名の横に札が入りません。
     * 落ちた瞬間に、その下の金額もボタンも 45.6px 下がります。
     */
    @Test
    @DisplayName("★ 列の左右の余白は 16px（24px にすると札が落ちる）")
    void theLaneKeepsTheDesignsSidePadding() throws Exception {
        String rule = block(".hallboard .lane__body");

        assertThat(rule).as(".hallboard .lane__body が無い").isNotEmpty();
        assertThat(rule.replaceAll("\\s+", " "))
                .as("列の左右の余白が設計（16px）とちがう。"
                        + "24px にするとカードが 15.3px 細くなり、卓名の横に札が入らなくなる")
                .contains("padding: 24px 16px");
    }

    /**
     * ★ 卓名の行は札の有無にかかわらず 36px（設計「ホール/頭」）。
     *
     * <p>床は「行」ではなく「中身」に敷きます。{@code .billcard__head} は
     * padding が 0 なので {@code min-height} がそのまま中身の床になります
     * （padding があると {@code box-sizing: border-box} に食われる）。
     */
    @Test
    @DisplayName("★ 卓名の行は札があっても無くても 36px")
    void theNameRowIsAlwaysTheSameHeight() throws Exception {
        String rule = block(".hallboard .billcard__head");

        assertThat(rule).as(".hallboard .billcard__head が無い").isNotEmpty();
        assertThat(rule.replaceAll("\\s+", ""))
                .as("卓名の行に高さの床が無い。札のあるカードと無いカードで 4px ずれ、"
                        + "下の金額とボタンも 4px ずれる")
                .contains("min-height:36px");
    }

    /**
     * ★ 札はちょうど 36px。枠は 0 にする。
     *
     * <p>素の {@code .badge} は {@code border: 1px solid transparent} を持っています。
     * 色を透明にしても<b>太さは残る</b>ので、上下 2px ぶん高くなって 38px になります。
     * 「border-color: transparent にしたから大丈夫」では直りません。
     */
    @Test
    @DisplayName("★ 札は 36px（枠 0・行間 20px）。透明枠でも太さは残る")
    void theBadgeIsExactlyThirtySixTall() throws Exception {
        String rule = block(".hallboard .billcard .badge").replaceAll("\\s+", "");

        assertThat(rule).as(".hallboard .billcard .badge が無い").isNotEmpty();
        assertThat(rule).as("札の枠を 0 にしていない。"
                + "透明でも 1px の太さは残るので 38px になり、卓名だけの行と 2px ずれる")
                .contains("border:0");
        assertThat(rule).as("札の行間が 20px でない。8＋20＋8＝36px にならない")
                .contains("line-height:20px");
        assertThat(rule).as("札の上下の余白が 8px でない").contains("padding:8px16px");
    }

    /**
     * ★ 金額の行は折り返さない（設計「Frame 2」280x39 NO_WRAP）。
     *
     * <p>折り返すと 39.2px → 80px に伸びて、そのカードのボタンだけ 42.8px 下がります。
     * 注記を消すのではなく、注記の中で折ります。12px の 2 行は 33.6px なので
     * 金額（39.2px）に収まり、行の高さは変わりません。
     */
    @Test
    @DisplayName("★ 金額の行は折り返さない。注記は中で折る")
    void theAmountRowNeverWraps() throws Exception {
        String amount = block(".hallboard .billcard__amount").replaceAll("\\s+", "");
        String note = block(".hallboard .billcard__note").replaceAll("\\s+", "");
        String yen = block(".hallboard .billcard__yen").replaceAll("\\s+", "");

        assertThat(amount).as(".hallboard .billcard__amount が無い").isNotEmpty();
        assertThat(amount).as("金額の行が折り返す。注記が長いカードだけ 80px に伸び、"
                + "そのカードのボタンだけ 42.8px 下がる")
                .contains("flex-wrap:nowrap");

        assertThat(yen).as("金額が縮んでしまう。縮めるのは注記のほうだけ")
                .contains("flex-shrink:0");

        // min-width:0 が無いと、flex の子は中身より小さくなれず行からあふれる
        assertThat(note).as("注記に min-width:0 が無い。"
                + "これが無いと注記は縮めず、nowrap でも行からあふれる")
                .contains("min-width:0");
    }

    /**
     * ★ カードの縦の積み方は設計どおり（gap 12・余白 24）。
     *
     * <p>設計のカードは 328x255。
     * 24 ＋ 頭 36 ＋ 12 ＋ 人数 20 ＋ 12 ＋ ラベル 20 ＋ 12 ＋ 金額 39 ＋ 12 ＋ ボタン 44 ＋ 24 ＝ 255。
     * 実測は 256.4px（14px の行間が 19.6px になるぶんだけの差）。
     */
    @Test
    @DisplayName("★ カードは縦 gap 12・余白 24（設計 328x255）")
    void theCardStacksLikeTheDesign() throws Exception {
        String rule = block(".hallboard .billcard").replaceAll("\\s+", "");

        assertThat(rule).as(".hallboard .billcard が無い").isNotEmpty();
        assertThat(rule).as("カードの段の間が 12px でない").contains("gap:12px");
        assertThat(rule).as("カードの余白が 24px でない").contains("padding:24px");
    }
}
