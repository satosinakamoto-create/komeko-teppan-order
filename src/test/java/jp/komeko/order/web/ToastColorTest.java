package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浮かせる札（toast）の色は 2 つだけ、という約束を見張る。
 *
 * <p><b>2026-09-20、店主の決定。</b>
 * 「警告ポップアップは赤、変更とかは緑で良いんじゃない？ポップアップは押せないものでしょ？」
 *
 * <pre>
 *   緑 … 変更が成った   掲載を止めました／品切れにしました／材料を外しました
 *   赤 … 警告           二度と入れません／選ばれていません／変わりませんでした
 * </pre>
 *
 * <p><b>青（info）を廃止した理由。</b><br>
 * それまで {@code flashSuccess} が緑、{@code flashInfo} が青で出ていました。
 * ところが中身を数えると、青の 25 件のうち 20 件が
 * 「掲載を止めました」「カートを空にしました」といった<b>緑と同じ種類の報告</b>でした。
 * 「掲載しました」は緑、「掲載を止めました」は青。色が何も言っていません。
 * 残る 5 件は「二度と入れません」「選ばれていません」という<b>警告</b>で、
 * これは青よりも赤が合っています。2 つに割ると、色が意味を持ちます。
 *
 * <p><b>2026-09-16 の「成功通知の面を緑で塗らない」との関係。</b><br>
 * あのときの理由は「押せるボタン色と似ていて、押せるものに見える」でした。
 * 浮かせる札は幅いっぱい・影つき・中央に出て、形がボタンとまるで違い、
 * <b>中に押せるものが 1 つもありません</b>（{@code pointer-events: none}）。
 * そのうえで念のため、<b>押せるボタンが使っている塗り</b>
 * （{@code --action} / {@code --ok-soft} / {@code --danger-soft}）は使いません。
 * ここを守るかぎり、色が濃くなってもボタンとは混ざりません。
 *
 * <p>このテストが無いと、あとから「info も足しておくか」と 3 色目が戻ってきます。
 * 色を増やすのは簡単で、増えた色の意味を思い出すのは難しい。
 */
class ToastColorTest {

    private static String css() throws Exception {
        return Files.readString(
                Path.of("src/main/resources/static/css/app.css"), StandardCharsets.UTF_8);
    }

    /** 「.toast.alert--xxx {」から対応する } までを切り出す。無ければ null。 */
    private static String ruleOf(String css, String selector) {
        int at = css.indexOf(selector + " {");
        if (at < 0) {
            return null;
        }
        return css.substring(at, css.indexOf("}", at));
    }

    /** #rrggbb から相対輝度（WCAG）。 */
    private static double luminance(String hex) {
        String h = hex.replace("#", "");
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) / 255.0;
            c[i] = (v <= 0.03928) ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** 2 色のコントラスト比（WCAG）。4.5 以上で本文として読める。 */
    private static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** 規則の中の「プロパティ: #rrggbb」を取り出す。 */
    private static String hexOf(String rule, String prop) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(prop + ":\\s*(#[0-9a-fA-F]{6})").matcher(rule);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- 読めること

    /**
     * ★★ 地を決め打ちにしたら、文字も決め打ちにすること。
     *
     * <p><b>2026-09-22 にこれで事故りました。</b>
     * 地は {@code background: #eef7f2} と決め打ちにしたのに、文字色は
     * {@code .alert--success} から降ってくる {@code var(--text)} のままでした。
     * お客さまの画面は {@code .theme-night}（暗い地）で {@code --text: #ffffff}。
     * つまり<b>薄緑の上に白文字</b>で、コントラスト 1.09:1。
     * 「注文リストに追加しました」がまったく読めませんでした。
     *
     * <p>赤の札も同じ穴で、{@code var(--danger)} は
     * 暗いテーマ {@code #e28a80} で 2.23:1、明るいテーマ {@code #d33f3f} でも
     * 4.01:1 と、<b>どちらのテーマでも足りていません</b>でした。
     *
     * <p>var() の色は var() の地とだけ組ませる。決め打ちの地には決め打ちの文字。
     * 浮かせる札はどのテーマの上にも同じ顔で出るので、テーマに依存させてはいけません。
     */
    @Test
    @DisplayName("★★ 浮かせる札は地も文字も決め打ちで、読めること（4.5:1 以上）")
    void toastsAreReadableOnEveryTheme() throws Exception {
        String css = css();
        for (String sel : List.of(".toast.alert--success", ".toast.alert--error")) {
            String rule = ruleOf(css, sel);
            assertThat(rule).as(sel + " が無い").isNotNull();

            String bg = hexOf(rule, "background");
            String fg = hexOf(rule, "color");
            assertThat(bg).as("★ " + sel + " の地が #rrggbb で書かれていない").isNotNull();
            assertThat(fg)
                    .as("★ " + sel + " に文字色が無い（テーマの var(--text) が降ってくる）。"
                            + "地を決め打ちにしたなら文字も決め打ちにすること")
                    .isNotNull();

            double ratio = contrast(fg, bg);
            assertThat(ratio)
                    .as("★ " + sel + " が読めない。文字 " + fg + " / 地 " + bg
                            + " = " + Math.round(ratio * 100) / 100.0 + ":1（4.5:1 以上が要る）")
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    // ---------------------------------------------------------------- 2 色ある

    @Test
    @DisplayName("★ 浮かせる札は緑（変更）と赤（警告）の 2 つがある")
    void thereAreTwoKindsOfToast() throws Exception {
        String css = css();

        assertThat(ruleOf(css, ".toast.alert--success"))
                .as("変更が成ったときの緑の札が無い")
                .isNotNull()
                .contains("background: #eef7f2");

        assertThat(ruleOf(css, ".toast.alert--error"))
                .as("警告の赤い札が無い")
                .isNotNull()
                .contains("background: #fdebeb");
    }

    // ---------------------------------------------------------------- 青は無い

    /**
     * ★ 3 色目を足させない。
     *
     * <p>青が残っていると、同じ「〜しました」が画面によって緑と青に割れます。
     * 割れていることは画面を 2 つ並べないと気づけないので、ここで止めます。
     */
    @Test
    @DisplayName("★ 青い札（info）は作らない。色は 2 つだけ")
    void thereIsNoBlueToast() throws Exception {
        assertThat(ruleOf(css(), ".toast.alert--info"))
                .as("青い札が戻ってきている。2026-09-20 に 2 色へ減らしたはず")
                .isNull();

        // 画面側も。data-toast="info" が残っていると、拾われて既定（緑）で出るため
        // 気づきにくい
        Path fragments = Path.of("src/main/resources/templates/fragments/common.html");
        assertThat(Files.readString(fragments, StandardCharsets.UTF_8))
                .as("data-toast=\"info\" が残っている")
                .doesNotContain("data-toast=\"info\"");
    }

    // ---------------------------------------------------------------- ボタンと混ざらない

    /**
     * ★ 押せるボタンの塗りは、押せない札に使わない。
     *
     * <p>2026-09-16 の指摘（「ポップアップの色が押せるボタン色と似てる」）の芯は
     * ここです。色を持たせること自体ではなく、<b>ボタンと同じ塗りを使うこと</b>が
     * 「押せそう」を生みます。淡い独自の色なら混ざりません。
     */
    @Test
    @DisplayName("★ どちらの札も、押せるボタンの塗りは使わない")
    void neitherToastUsesAPressableFill() throws Exception {
        String css = css();
        for (String sel : List.of(".toast.alert--success", ".toast.alert--error")) {
            String rule = ruleOf(css, sel);
            assertThat(rule).as(sel + " が無い").isNotNull();
            for (String pressable : List.of("var(--action)", "var(--ok-soft)",
                    "var(--danger-soft)", "#0b7a1a")) {
                assertThat(rule)
                        .as("★ " + sel + " が押せるボタンの塗り " + pressable + " を使っている")
                        .doesNotContain("background: " + pressable);
            }
        }
    }

    /**
     * ★ 札は押せないこと。
     *
     * <p>店主の言葉「ポップアップは押せないものでしょ？」が、色を付けてよい理由です。
     * 押せなくしてあるから色で意味を言える、という関係なので、
     * {@code pointer-events: none} が外れたらこの前提ごと崩れます。
     */
    @Test
    @DisplayName("★ 札は押せない（だから色で意味を言ってよい）")
    void toastsAreNotClickable() throws Exception {
        assertThat(ruleOf(css(), ".toast-stack"))
                .as("浮かせる入れ物が押せる状態になっている")
                .isNotNull()
                .contains("pointer-events: none");
    }

    // ---------------------------------------------------------------- 仕分けが合っている

    /**
     * ★ 「警告」に見える文が緑で出ていないか。
     *
     * <p>仕分けの見分け方を全部は決め打ちできませんが、
     * <b>次の 2 つだけは落とせません</b>。どちらも緑で出ると意味が反転します。
     *
     * <pre>
     *   「二度と」「もう使えません」… 取り返しがつかない。緑だと「できた」だけが伝わる
     *   「変わりませんでした」「すでにいちばん」… <b>何も起きていない</b>。
     *                                  緑だと「やった」と誤解して、もう一度押さない
     * </pre>
     *
     * <p>2 つめは 2026-09-20 に実際に取りこぼしました。
     * 「並び順は変わりませんでした」は移したのに、同じ意味の
     * 「このカテゴリの中では、すでにいちばん上です」が緑のまま残っていました。
     * 文言が違うだけで中身は同じ「何も起きていない」です。
     */
    @Test
    @DisplayName("★ 取り返しのつかない報告・何も起きていない報告は緑で出さない")
    void warningsAreNotGreen() throws Exception {
        List<String> green = List.of("flashSuccess", "flashInfo");
        List<String> mustBeRed = List.of("二度と", "もう使えません",
                "変わりませんでした", "すでにいちばん", "選ばれていません");

        int seen = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> javas = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javas).as("Java が 1 つも見つからない（探す場所が違う）").isNotEmpty();

            for (Path p : javas) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (String k : green) {
                    int at = 0;
                    while ((at = src.indexOf('"' + k + '"', at)) >= 0) {
                        seen++;
                        // その呼び出しから、文の終わり（;）までを 1 件ぶんとして見る
                        int end = src.indexOf(';', at);
                        String one = (end < 0) ? src.substring(at) : src.substring(at, end);
                        for (String red : mustBeRed) {
                            assertThat(one)
                                    .as("★ " + p.getFileName() + " の " + k + " に「" + red
                                            + "」が入っている。これは警告なので flashWarn（赤）へ")
                                    .doesNotContain(red);
                        }
                        at = at + k.length();
                    }
                }
            }
        }
        // ★ 0 件で素通りしないこと。探し方を間違えると全部通ってしまう
        assertThat(seen).as("緑の呼び出しが 1 件も見つからない。探し方が違う").isGreaterThan(50);
    }
}
