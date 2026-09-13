package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帯・見出し・サイドバーの寸法を Render のダッシュボードにそろえる（2026-09-13）。
 *
 * <p><b>どこから来た値か</b><br>
 * 店主が参考に挙げた dashboard.render.com を開いて computed style を読んだ値です。
 * スクリーンショットからの目測ではありません（それで 1 度読み違えています）。
 * <pre>
 *   帯          高さ 56px ／ 主要な文字 16px ／ 補助 13px
 *   見出し h1    32px / 行間 36px
 *   節の見出し    20px / 行間 28px
 *   サイドバー    項目 16px / 行間 24px
 * </pre>
 *
 * <p><b>なぜ「固定」にするのか</b><br>
 * 32 節（広い画面）は、これまで文字も一緒に大きくしていました
 * （サイドバー 15 → 17 → 19px、営業日 17 → 19 → 21px など）。
 * Render は 3807px の画面でも 16px のままです。店主はその画面を見て
 * 「サイドバーの 15px は小さい」と言っているので、
 * <b>基準を上げて、画面幅による増減はやめる</b>のが求められている形です。
 * 表や数字カード（32 節の残り）は情報量が幅に比例するので、あちらは据え置きます。
 *
 * <p><b>そろえなかったもの</b>
 * <ul>
 *   <li>項目の高さ … Render は 36px。CLAUDE.md「タップ領域は 48px 以上」を優先して 48px のまま</li>
 *   <li>サイドバーのアイコン … Render は 16px。タブレットで畳んだとき 16px では見分けが付かないので 20px のまま</li>
 *   <li>見出しの太さ … Render は 500／600。日本語は 700 でないと細く見えるので 700 のまま</li>
 *   <li>本文の左右余白 … Render は 48px 固定。店主の「もっと余白を」の指示で 88〜160px にしてあるので据え置き</li>
 * </ul>
 */
@DisplayName("帯・見出し・サイドバーを Render にそろえる")
class RenderAlignedTypeTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 32 節（ファイル末尾の「広い画面」）だけを切り出す。
     *  ★ "@media (min-width: 1440px)" を直接 indexOf しないこと。
     *    35-1 の注記が同じ文字列を含んでいて、そこから切り出すと
     *    40 節（max-width の段差）まで「広い画面」として検査してしまう。
     *    実際 40 節を足した日に .page-head__title で空振りした。 */
    private String wideBands() throws Exception {
        String css = css();
        int sec = css.indexOf("32. 広い画面");
        assertThat(sec).as("32 節が無い").isGreaterThan(0);
        int at = css.indexOf("@media (min-width: 1440px)", sec);
        assertThat(at).as("32 節の media が無い").isGreaterThan(sec);
        return css.substring(at).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ 帯は 56px。画面幅で高さを変えない")
    void theTopbarIsFiftySix() throws Exception {
        String css = css();

        assertThat(css).contains("--topbar-h: 56px;");
        // 96px / 104px の段は廃止。Render は 3807px でも 56px のまま
        assertThat(wideBands()).doesNotContain("--topbar-h");
    }

    @Test
    @DisplayName("★ 帯の主要な文字は 16px（店名・受付の状態・未提供・営業日）")
    void theTopbarTextIsSixteen() throws Exception {
        String css = css();

        assertThat(css).contains(".topbar__brand-name { display: block; font-size: 16px;");
        assertThat(css).contains(".topbar__pending-count { font-size: 16px; font-weight: 700;");
        assertThat(css).contains(".topbar__day-value { font-size: 16px; font-weight: 700;");

        // 幅で文字を変えない
        String wide = wideBands();
        assertThat(wide).doesNotContain(".topbar__brand-name");
        assertThat(wide).doesNotContain(".topbar__day-value");
        assertThat(wide).doesNotContain(".topbar__state   { font-size");
    }

    @Test
    @DisplayName("★ 見出しは 32px/36px、節の見出しは 20px/28px")
    void headingsMatchRender() throws Exception {
        String css = css();

        assertThat(css).contains(".page-head__title  { font-size: 32px; font-weight: 700; margin: 0; line-height: 36px; }");
        // 厨房ボードだけ 28px で一段小さかった。同じ「ページの題」なのでそろえる
        assertThat(css).contains(".kitchenboard .griddle h1 { font-size: 32px; line-height: 36px; }");
        assertThat(css).contains(".panel__title { font-size: 20px; font-weight: 700; margin: 0; line-height: 28px; }");

        String wide = wideBands();
        assertThat(wide).doesNotContain(".page-head__title");
        assertThat(wide).doesNotContain(".panel__title");
    }

    @Test
    @DisplayName("★ サイドバーの項目は 16px/24px（15px は小さいという指摘）")
    void theSidebarItemIsSixteen() throws Exception {
        String css = css();

        int at = css.indexOf(".sb__item {");
        assertThat(at).as(".sb__item の指定が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("font-size: 16px;");
        assertThat(rule).contains("line-height: 24px;");

        // 幅で「文字」を変えない。寸法（角丸・余白）は 32 節に残ってよい
        String wide = wideBands();
        assertThat(wide).doesNotContain(".sb__item  { font-size");
        assertThat(wide).doesNotContain(".sb__label { font-size");
        assertThat(wide).doesNotContain(".sb__account-name { font-size");
    }

    @Test
    @DisplayName("★ そろえないもの：タップ 48px とアイコン 20px は残す")
    void theTapRuleAndIconSizeSurvive() throws Exception {
        String css = css();

        int at = css.indexOf(".sb__item {");
        String rule = css.substring(at, css.indexOf("}", at));
        // Render の項目は 36px だが、こちらは指で押す画面でもある
        assertThat(rule).contains("min-height: var(--tap);");

        // Render のアイコンは 16px。畳んだサイドバー（64px 幅）で見分けが付かなくなる
        assertThat(css).contains(".sb .ic { width: 20px; height: 20px;");
    }
}
