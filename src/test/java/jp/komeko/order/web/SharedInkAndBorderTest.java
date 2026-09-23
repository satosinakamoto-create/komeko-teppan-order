package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画面をまたいで共通に使う色・太さ・大きさの、揃え先を見張る。
 *
 * <p><b>2026-09-22。</b>42 画面の実測監査で B（測って分かる食い違い）が 232 件出ました。
 * うち systemic と印が付いたものが 55 件。ところが中身を見ると<b>主題は 9 つだけ</b>で、
 * 同じ 1 行の CSS が何十画面にも出ていただけでした。
 * 店主の判断は「大体はフィグマ通りに作って欲しい／黒は #1c1c1c、グレーは #828282」。
 *
 * <p>ここで見張るのは<b>揃え先の値そのもの</b>です。
 * 「画面ごとにばらつかせない」を見ているのは {@code VerticalRhythmIsSharedTest} で、
 * こちらは「揃えた先が設計（または店主の決定）の値か」を見ます。
 *
 * <pre>
 *   白ボタンの枠   #c7c7c7 → #e8e8e8   設計に寄せる
 *   数値セルの太さ 600     → 400       設計に寄せる
 *   補足のグレー   実効 #828282        店主の決定（不透明で書く）
 *   件数の字       16px    → 14px      設計に寄せる
 *   ラベルの太さ   700     → 500       設計に寄せる
 *   btn--sm        40px    → 48px      店主の決定（タップ 48px。設計より上）
 *   入力欄の枠     実効 #dfdfdf → #c7c7c7  設計の多数派に寄せる
 *   淡緑           #e6f4e8 → #ddf0e0   設計に寄せる
 *   押せない帯の緑 やめる               「緑は押せるものだけ」の決まり
 * </pre>
 *
 * <p><b>入力欄の角丸は 8px のままです。</b>設計は 0px ですが、
 * 店主の決定「ボタンも札も 8px」が設計より上に来ます。ここは設計を直す側。
 */
class SharedInkAndBorderTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private static String css() throws Exception {
        return Files.readString(CSS, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** コメントを外した本文。説明文に書いた値を拾わないため。 */
    private static String rules() throws Exception {
        return css().replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * 「セレクタ {」から対応する } までを切り出す。
     *
     * <p>★ 行頭から探すこと。{@code indexOf(".label {")} だと
     * {@code .formcard span.label {}（603 行）に先に当たります。
     * 同じ罠を {@code ActionGreenTokenTest} でも踏んでいて、
     * あちらにも「行頭から探せ」と書いてあります。
     */
    private static String ruleOf(String css, String selector) {
        int at = css.indexOf("\n" + selector + " {");
        if (at < 0) {
            return null;
        }
        return css.substring(at + 1, css.indexOf("}", at));
    }

    // ---------------------------------------------------------------- 枠と面

    @Test
    @DisplayName("★ 白ボタンの枠は設計の #e8e8e8")
    void theOutlinedButtonBorderMatchesTheDesign() throws Exception {
        String rule = ruleOf(rules(), ".theme-desk .btn");
        assertThat(rule).as(".theme-desk .btn が無い").isNotNull();
        assertThat(rule)
                .as("★ 白ボタンの枠が設計（#e8e8e8）でない。"
                        + "#c7c7c7 は 6 グループの監査で同じ指摘を受けた古い値")
                .contains("--btn-bd: #e8e8e8;");
    }

    @Test
    @DisplayName("★ 入力欄の枠は #c7c7c7・角丸は 8px（角丸だけは設計より決定が上）")
    void theInputBorderMatchesTheDesign() throws Exception {
        String rules = rules();
        String rule = ruleOf(rules, ".theme-snow .input,\n.theme-snow .select,\n.theme-snow .textarea");
        assertThat(rule).as(".theme-snow の入力欄の規則が無い").isNotNull();
        assertThat(rule)
                .as("★ 入力欄の枠が #c7c7c7 でない。透明度（rgba）で作ると、"
                        + "淡い札の上に置いたときだけ色が変わる")
                .contains("border-color: #c7c7c7;");

        // ★ 角丸は 8px のまま。設計の 0px を追わないこと（店主の決定が上）
        assertThat(ruleOf(rules, ".input, .select, .textarea"))
                .as("★ 入力欄の角丸が 8px でない（店主の決定「ボタンも札も 8px」）")
                .contains("border-radius: 8px;");
    }

    @Test
    @DisplayName("★ 押せるものの淡い面は設計の #ddf0e0")
    void thePaleGreenMatchesTheDesign() throws Exception {
        assertThat(rules())
                .as("★ --action-soft が設計の #ddf0e0 でない。"
                        + "サイドバーの現在地・アカウントの丸・未処理札などが一斉にずれる")
                .contains("--action-soft:   #ddf0e0;");
    }

    // ---------------------------------------------------------------- 文字

    @Test
    @DisplayName("★ 補足のグレーは #828282（店主の決定・不透明で書く）")
    void theMutedGreyIsOpaque() throws Exception {
        String rules = rules();
        assertThat(rules)
                .as("★ .theme-snow の --text-muted が #828282 でない")
                .contains("--text-muted:    #828282;");

        // 件数（節の補足）も同じグレーに揃える。--text-faint（実効 #a9a9a9）は
        // 白地で 2.35:1 しかなく、読める濃さではない
        assertThat(ruleOf(rules, ".section-title__count"))
                .as("★ 件数が --text-faint（薄すぎる #a9a9a9）に戻っている")
                .contains("color: var(--text-muted);");
    }

    @Test
    @DisplayName("★ 表の数値は Regular（400）。太らせない")
    void theNumbersAreNotBold() throws Exception {
        assertThat(ruleOf(rules(), ".theme-desk .table td.right"))
                .as("★ 数値セルが太い。設計はどの表も Regular で、"
                        + "太らせると金額だけが見出しより強く見える")
                .contains("font-weight: 400;");
    }

    @Test
    @DisplayName("★ 項目名は Medium（500）。700 は設計のどの値より太い")
    void theFormLabelIsNotBold() throws Exception {
        assertThat(ruleOf(rules(), ".label"))
                .as("★ ラベルが太い。設計は ト10b が 500、食材の画面が 400")
                .contains("font-weight: 500;");
    }

    // ---------------------------------------------------------------- 押せるもの

    /**
     * ★★ 小さいボタンも 48px を割らないこと。
     *
     * <p>CLAUDE.md の「タップ領域は 48px 以上」に、実装が自分で違反していました。
     * 税理士の「直す」60 個・「開く」11 個、カテゴリの「編集する」15 個が 40px。
     * <b>設計も h40 で描かれていますが、それでも 48px が正</b>です
     * （品切れ・残数の行高で同じ判断をしてあり、そこにも同じ理由が書いてあります）。
     */
    @Test
    @DisplayName("★★ btn--sm も 48px 以上（設計が 40px でも決定が上）")
    void theSmallButtonStillMeetsTheTapTarget() throws Exception {
        assertThat(ruleOf(rules(), ".theme-desk .btn--sm"))
                .as("★ btn--sm が 48px を割っている。CLAUDE.md のタップ領域の約束違反")
                .contains("min-height: 48px;");
    }

    /**
     * ★★ 押せない帯に、押せるものの緑を使わないこと。
     *
     * <p>{@code .theme-desk} は {@code --accent} を {@code --action}（押せる緑）に
     * 差し替えています。そのため {@code .alert--warn} だけが
     * <b>押せないのに押せる緑</b>で出ていました（税理士 2 画面で実測）。
     * 「緑は押せるものだけ」という決まりの、いちばん大きな破れでした。
     *
     * <p>逆に日付ナビの前月・翌月は<b>押すもの</b>なので、緑にするのが正しい。
     * 同じ「緑」の話でも向きが逆なので、両方をここで見ます。
     */
    @Test
    @DisplayName("★★ 緑は押せるものだけ（常設の案内は面だけ・押すものには枠か塗り）")
    void greenMeansPressable() throws Exception {
        String rules = rules();

        // ★ 2026-09-23：店主の判断で、机の画面の常設案内を緑に戻しました
        //   （「茶色のpopアップは緑に統一して」）。
        //   きつね色は 2026-09-22 に「緑は押せるものだけ」を守るために入れた色でしたが、
        //   白と緑で組んだスタッフ画面では茶色が浮いていた、というのが決め手です。
        //
        //   色をボタンと共有するかわりに、<b>形で見分けます</b>。
        //   帯は面だけを持ち、枠も塗りも持ちません。押せるものは必ずどちらかを持ちます。
        //   だからここで見るのは「緑かどうか」ではなく「枠や塗りを持っていないか」です。
        String warn = ruleOf(rules, ".theme-desk .alert--warn");
        assertThat(warn)
                .as("★ 机の画面の常設案内が無い")
                .isNotNull()
                .contains("var(--action)");
        assertThat(warn)
                .as("★★ 押せない帯が枠を持っている。ボタンと見分けがつかなくなる")
                .doesNotContain("border");

        assertThat(ruleOf(rules, ".theme-desk .datenav .btn"))
                .as("★ 日付ナビの前月・翌月が灰色のまま。これは押すものなので緑が正しい")
                .isNotNull()
                .contains("--btn-bd: #0e9721;");
    }

    // ---------------------------------------------------------------- 素通り防止

    /**
     * ★ 古い値がどこかに残っていないこと。
     *
     * <p>上の 1 本ずつは「新しい値があるか」を見ています。
     * これは「<b>古い値が別の場所に残っていないか</b>」を見ます。
     * 同じ色を 2 箇所に書いていると、片方だけ直して揃ったつもりになれます。
     */
    @Test
    @DisplayName("★ 直した古い値が別の場所に残っていない")
    void theOldValuesAreGone() throws Exception {
        String rules = rules();
        for (String gone : List.of("--btn-bd: #c7c7c7;", "--action-soft:   #e6f4e8;",
                "--text-muted:    rgba(28, 28, 28, .55);")) {
            assertThat(rules).as("★ 古い値が残っている: " + gone).doesNotContain(gone);
        }
        assertThat(rules.contains("--btn-bd: #e8e8e8;"))
                .as("そもそも新しい値が 1 つも無い（探し方が違う）").isTrue();
    }
}
