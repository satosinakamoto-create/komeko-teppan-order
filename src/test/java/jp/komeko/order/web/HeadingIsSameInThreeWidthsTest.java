package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 見出しが 3 つの幅（1432 / 1024 / 1920）でズレないこと（2026-09-19、店主の指示）。
 *
 * <p>店主の言葉は「揃えておいて３パターンズレないようにね」
 * ＝「3 パターンとも確認してね」。実測したら 2 か所ズレていました。
 *
 * <h2>① iPad（1024px）で 3 画面だけ畳まれていなかった</h2>
 * <pre>
 *   画面        題の x    帯の高さ
 *   ホール       128       82      ← 畳まれていない
 *   品切れ       128       82      ← 畳まれていない
 *   食材         128       82      ← 畳まれていない
 *   ほかの 8 枚   112       74
 * </pre>
 *
 * <p>題を {@code .page-head} ではなく {@code .section-title} 系で出している 3 枚で、
 * {@code .page-head { padding: 12px 24px }} が当たっていませんでした。
 *
 * <h2>② 税理士だけ題が 40px 左に飛び出していた</h2>
 * <pre>
 *   画面      題の左    見出しの左右余白
 *   税理士     288       0 / 0     ← 本文の左端に貼り付いていた
 *   商品       328       40 / 40
 *   売上       328       40 / 40
 * </pre>
 *
 * <h2>直したあと（3 幅すべて実測）</h2>
 * <pre>
 *   幅       題の x   題の箱   字    帯の高さ
 *   1432px    328      50      32     82      12 画面すべて一致
 *   1024px    112      50      28     74      12 画面すべて一致
 *   1920px    428      50      32     82      12 画面すべて一致
 * </pre>
 *
 * <p><b>残っている差は税理士の上下 56px だけです。</b>
 * 税理士のレイアウトには店舗管理の帯（topbar）が無いので、画面ごと 56px 上にいます。
 * これは見出しの余白ではなくレイアウトの作りの話なので、ここでは見ていません。
 *
 * <p>このテストは<b>幅ごとの値が 1 種類であること</b>を CSS の指定で守ります。
 * 画面を描いて測るのは probe ページ（{@code target/allscreens/_preview-3widths.html}）の仕事です。
 */
@DisplayName("見出しは 3 つの幅でズレない")
class HeadingIsSameInThreeWidthsTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** コメントを外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * セレクタの宣言ブロックの中身を取り出す（空白は詰める）。
     *
     * <p>★ 「セレクタ＋padding」の並びで探さないこと。
     * {@code .page-head} は display / align-items / gap を先に書いていて、
     * padding は 4 行目です。並びで探すと、正しく書いてあるのに落ちます
     * （2026-09-19 に実際そうなりました）。
     *
     * <p>★ 同じセレクタが複数あることも前提にすること。
     * {@code .hallboard .section-title:has(h1)} は最初の 1 つが
     * {@code margin-top: 0} だけの別の規則で、寸法は 2 つ目にあります。
     * 1 つ目だけ見ると、正しく書いてあるのに落ちます（これも実際に踏みました）。
     *
     * @return そのセレクタで始まる宣言ブロックを、出てくる順に全部
     */
    private List<String> blocks(String selector) throws Exception {
        String css = css();
        List<String> found = new java.util.ArrayList<>();
        int at = css.indexOf(selector);
        while (at >= 0) {
            int open = css.indexOf('{', at);
            int close = css.indexOf('}', open);
            if (open < 0 || close < 0) break;
            found.add(css.substring(open + 1, close).replaceAll("\\s+", ""));
            at = css.indexOf(selector, at + 1);
        }
        return found;
    }

    /** そのセレクタのどれかのブロックに、その宣言が書かれているか。 */
    private boolean declares(String selector, String decl) throws Exception {
        return blocks(selector).stream().anyMatch(b -> b.contains(decl));
    }

    /**
     * ★ 基準の幅（1432 / 1920）では、見出しを出す 5 つの作りが全部 16px 40px。
     *
     * <p>題の出し方が 5 通りあります。どれか 1 つを直して残りを忘れる、が
     * この画面群でいちばん起きやすい事故です（厨房・ホール・品切れ・食材・税理士で
     * 実際に起きました）。
     */
    @Test
    @DisplayName("★ 1432 / 1920px では 5 つの作りが全部 16px 40px")
    void everyHeadingUsesTheSameInsetAtBaseWidth() throws Exception {
        List<String> selectors = List.of(
                ".page-head {",                             // 商品・卓・注文履歴ほか
                ".kitchenboard .griddle .card__body",       // 厨房
                ".hallboard .section-title:has(h1)",        // ホール
                ".soldoutpage .soldout-head",               // 品切れ
                ".stockpage .inv-ingredients");             // 食材

        for (String s : selectors) {
            assertThat(blocks(s)).as(s + " が見つからない").isNotEmpty();
            assertThat(declares(s, "padding:16px40px"))
                    .as(s + " が 16px 40px でない。題の左が 40px ずれる")
                    .isTrue();
        }

        // 税理士は上下と左右を別々に書いている（器の作りが違うため）
        assertThat(css().replaceAll("\\s+", ""))
                .as("税理士の見出しの左右が 40px でない。本文の左端に貼り付く")
                .contains(".theme-ledger.section-title:has(h1){padding-block:16px;padding-inline:40px;}");
    }

    /**
     * ★ 狭い画面（iPad・1024px）でも 5 つ全部が 8px 24px に畳む。
     *
     * <p>{@code .page-head} だけ畳んでも、{@code .section-title} 系の 3 画面が残ります。
     */
    @Test
    @DisplayName("★ 1024px では 5 つの作りが全部 8px 24px に畳む")
    void everyHeadingFoldsTogetherOnIpad() throws Exception {
        String css = css().replaceAll("\\s+", "");

        List<String> rules = List.of(
                ".page-head{padding:8px24px;}",
                ".kitchenboard.griddle.card__body{padding:8px24px;}",
                ".hallboard.section-title:has(h1){padding:8px24px;}",
                ".soldoutpage.soldout-head{padding:8px24px;}",
                ".stockpage.inv-ingredients{padding:8px24px;}");

        for (String r : rules) {
            assertThat(css).as(r + " が無い。iPad でこの画面だけ題が 16px 右に残る").contains(r);
        }

        // ★ 2026-09-26：ここも 12px → 8px。税理士 5 画面も同じ帯を使っているので、
        //   置いていくと店舗側 20 画面が 64px・税理士が 74px に割れます（実測で気づきました）。
        assertThat(css).as("税理士が iPad で畳まれていない")
                .contains(".theme-ledger.section-title:has(h1){padding-block:8px;padding-inline:24px;}");
    }

    /**
     * ★ .section-title 系の畳みには 700px の下限を付けること。
     *
     * <p>699px 以下には別の指定（{@code .hallboard} は 16px 20px）があり、
     * そちらはこの規則より<b>前</b>にあります。下限を付けないと後ろのこれが勝って、
     * 699px 以下の指定が死にます。
     */
    @Test
    @DisplayName("★ 畳みの帯には 700px の下限が付いている（699px 以下を殺さないため）")
    void theFoldHasALowerBound() throws Exception {
        String css = css();

        int at = css.indexOf("@media (max-width: 1380px) and (min-width: 700px)");
        assertThat(at)
                .as("下限 700px の帯が無い。699px 以下の .hallboard 16px 20px が死ぬ")
                .isGreaterThan(0);

        String block = css.substring(at, css.indexOf("\n}", at));
        assertThat(block).contains(".hallboard .section-title:has(h1)");
        assertThat(block).contains(".soldoutpage .soldout-head");
        assertThat(block).contains(".stockpage .inv-ingredients");

        // 699px 以下の指定が、この帯より前にあること（後ろだと勝ってしまう）
        int narrow = css.indexOf(".hallboard .section-title:has(h1) { padding: 16px 20px;");
        assertThat(narrow).as("699px 以下の .hallboard の指定が無い").isGreaterThan(0);
        assertThat(narrow)
                .as("699px 以下の指定が畳みの帯より後ろにある。"
                        + "前後が入れ替わると、下限 700px を付けた意味が無くなる")
                .isLessThan(at);
    }

    /**
     * ★ 題の箱は 5 つとも 50px。
     *
     * <p>箱の高さが違うと、字の中心がズレます（厨房が 36px のままで 3px 上にいました）。
     */
    @Test
    @DisplayName("★ 題の箱は 50px（.page-head・.section-title・厨房の 3 系統とも）")
    void everyTitleBoxIsFifty() throws Exception {
        String css = css().replaceAll("\\s+", "");

        assertThat(css).as(".page-head__title の床が無い")
                .contains(".page-head__title{");
        assertThat(css).as(".section-title__text の床が無い")
                .contains(".theme-deskh1.section-title__text{min-height:50px;display:flex;align-items:center;}");
        assertThat(css).as("厨房の題の床が無い")
                .contains(".kitchenboard.griddleh1{min-height:50px;display:flex;align-items:center;}");
    }
}
