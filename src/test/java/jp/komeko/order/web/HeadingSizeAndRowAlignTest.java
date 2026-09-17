package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 見出しの大きさと、カテゴリ行の縦位置（2026-09-17、店主の指摘）。
 *
 * <p><b>① ホール・会計の題だけ小さかった。</b>実測で 28px、ほかの画面は 32px です。
 * 設計（ト02 725:2103）も 28px でしたが、<b>これは設計側の取りこぼしと判断しました。</b>
 *
 * <p>理由は同じ CSS の 3 行上に書いてあります——この画面は帯の上下を 20px に
 * 厚くしてあり、コメントには「立って見る画面だけ帯を厚くする、という判断が
 * 入っています」とあります。<b>立って見る画面で題だけ小さいのは筋が通りません。</b>
 * Figma の他の画面（ダッシュボード・品切れ・残数・商品・売上）はすべて 32px です。
 *
 * <p><b>② カテゴリ行の ↑↓ と削除が下に沈んで見えた。</b>実測はこうでした。
 *
 * <pre>
 *   入力欄 81〜129（高さ 48）
 *   更新   81〜129（高さ 48）
 *   ↑      89〜129（高さ 40）   ← 上が 8px 下
 *   削除   89〜129（高さ 40）
 * </pre>
 *
 * <p>行は下揃え（{@code align-items: flex-end}）なので底はそろいますが、
 * 40px のものだけ頭が 8px 下がります。
 *
 * <p>そのうえ 40px は CLAUDE.md の「タップ領域は 48px 以上」を割っています。
 * 品切れ・残数の表でも同じことが起きていて、そこには
 * 「押せるものなので 48px は割らない」と書いて個別に直してありました。
 * ここも同じ理由で 48px にそろえます。並びの問題とタップ領域の問題が、
 * 同じ 1 つの直しで片付きます。
 */
@DisplayName("見出しの大きさとカテゴリ行の縦位置")
class HeadingSizeAndRowAlignTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path CATEGORIES =
            Path.of("src/main/resources/templates/admin/categories.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------
    // ① 見出しの大きさ
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ ホール・会計の題も 32px（他の画面とそろえる）")
    void theHallTitleIsNotSmallerThanTheRest() throws Exception {
        String css = css();

        int at = css.indexOf(".hallboard h1.section-title__text");
        assertThat(at).as("ホール・会計の題の規則が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("ホール・会計の題だけ 28px のまま").doesNotContain("28px");
        assertThat(rule).as("32px になっていない").contains("32px");
    }

    /**
     * ★ 他の画面の題が 32px から下がっていないこと。
     *
     * <p>狭い画面向けの上書き（メディアクエリの中）は対象外です。
     * あちらは意図して下げています。
     */
    @Test
    @DisplayName("★ 机で見る画面の題は 32px から下がっていない")
    void noScreenShrinksItsPageTitle() throws Exception {
        String css = css();

        // メディアクエリの中身を外してから探す
        String flat = css.replaceAll("(?s)@media[^{]*\\{.*?\\n\\}", "");

        Matcher m = Pattern.compile(
                "(?m)^([^{}\\n]*(?:h1\\.section-title__text|page-head__title)[^{}\\n]*)\\{([^{}]*)\\}")
                .matcher(flat);

        StringBuilder small = new StringBuilder();
        while (m.find()) {
            // 題の右に出る件数（「／ 掲載中 94 品」など）は補足なので対象外。
            // 小さいのが正しい。
            if (m.group(1).contains("section-title__count")) {
                continue;
            }
            Matcher f = Pattern.compile("font-size:\\s*(\\d+)px").matcher(m.group(2));
            if (f.find() && Integer.parseInt(f.group(1)) < 32) {
                small.append("\n  ").append(m.group(1).trim())
                        .append("  → ").append(f.group(1)).append("px");
            }
        }

        assertThat(small.toString())
                .as("題が 32px より小さい画面がある:%s", small)
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // ② カテゴリ行の縦位置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ カテゴリ行の ↑↓・削除も 48px（行の頭がそろう）")
    void theCategoryRowButtonsAreFullHeight() throws Exception {
        String css = css();

        // ★ .theme-desk から書くこと。.catlist .btn--sm だけ（0,2,0）だと、
        //   3557 行の .theme-desk .btn--sm（同じ詳細度で、より後ろ）に負ける。
        //   実際に一度それで効かず、実測 40px のままだった。
        int at = css.indexOf(".theme-desk .catlist .btn--sm");
        assertThat(at).as(".theme-desk .catlist .btn--sm の規則が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("48px になっていない。40px のままだと頭が 8px 下がる")
                .contains("min-height: 48px");
    }

    @Test
    @DisplayName("★ カテゴリの一覧に目印（.catlist）が付いている")
    void theCategoryListHasItsMarker() throws Exception {
        String html = Files.readString(CATEGORIES).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
        assertThat(html).as("一覧に .catlist が無い").contains("class=\"stack catlist\"");
    }
}
