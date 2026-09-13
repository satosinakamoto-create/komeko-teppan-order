package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 広い画面の本文を「上限で止める」のではなく「左右に余白を取って伸ばす」形に固定する
 * （2026-09-13）。
 *
 * <p><b>なぜ変えたのか</b><br>
 * 前日に 35-5 で {@code .kitchenboard / .hallboard} を {@code max-width: 1120px} ＋
 * {@code margin-inline: auto} にしました。2560px で左右に 506px ずつの余白ができる形です。
 * これは「余白を作る」ことには成功していますが、<b>使える幅を捨てて</b>作っています。
 *
 * <p>店主から参考として渡された管理画面（Render のダッシュボード）を測ると、
 * 1999px 幅の窓で サイドバー 232px・本文の左右余白 158px／171px・表の幅 1438px でした。
 *
 * <p><b>★ ここで 1 度読み違えています（2026-09-13 に訂正）。</b><br>
 * 上の数字はスクリーンショットから測ったもので、そこから
 * 「Render は上限で止めず、幅を最後まで使っている」と結論しました。<b>誤りです。</b>
 * 実物を開いて測ると {@code main} は {@code max-width: 1920px} ＋
 * {@code margin-inline: auto} ＋ {@code padding-inline: 48px} でした。
 * 1999px の窓ではサイドバーを引いた 1767px が 1920px に届かないので、
 * <b>上限が効いていない状態だけを見ていた</b>ことになります。
 *
 * <p>正しくは「1120px という<b>小さすぎる</b>上限をやめる」が結論で、
 * 「上限そのものをやめる」ではありませんでした。3807px の画面では本文が
 * 3155px まで伸び、厨房のレーンが 1 本 1040px になります。
 * 上限は {@code widthIsCappedLikeRender()} で入れ直してあります。
 *
 * <p><b>ここで守るもの</b>
 * <ol>
 *   <li>盤面に幅の上限を戻さないこと（{@code max-width} ＋ {@code margin-inline:auto} の再発防止）</li>
 *   <li>ガターは 1920px で 88px。Figma「PC01 厨房ボード（1920・左右に余白）」の実測値</li>
 *   <li>ホールの列も伸びること。360px 固定のままだと、厨房だけ伸びて 2 画面がちぐはぐになる</li>
 * </ol>
 *
 * <p>CSS を読むだけのテストなので Spring を起動しません（数百ミリ秒で終わります）。
 */
@DisplayName("広い画面は上限で止めず、左右の余白で読ませる")
class WideScreenGutterTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    /** CRLF のまま複数行を contains すると必ず外れるので、読んだ時点で LF にそろえる。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * コメントを落とした CSS。<b>doesNotContain は必ずこちらに掛けること。</b>
     *
     * <p>この app.css は「なぜそうしたか」を長く書く方針なので、
     * 禁止したい文字列がそのまま注意書きに出てきます。実際このテストも
     * 「ここに {@code --main-pad-x} を書かないこと」という注意書きに一致して落ちました。
     * TopbarDesignTest が th:classappend で、StockCategoryPickTest が .stock-chip で
     * 踏んだのと同じ罠です。
     */
    private String cssWithoutComments() throws Exception {
        return css().replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * 宣言ブロック（{@code セレクタ { … }}）だけを切り出す。
     * ファイル全体に対する doesNotContain は、別の部品が同じ値を持っていると落ちるので使わない。
     */
    private String block(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    @Test
    @DisplayName("★ 盤面に幅の上限を戻さない（35-5 の max-width / margin-inline:auto は廃止）")
    void boardsAreNotCappedAnyMore() throws Exception {
        String css = cssWithoutComments();

        // 35-5 が置いていた 1 本そのもの。復活したらここで止める。
        // （margin-inline: auto はお客さま側で 8 か所使っているので、
        //   ファイル全体に対する doesNotContain では書けない）
        assertThat(css)
                .as("上限で止めると、使える幅を捨てて余白を作ることになる")
                .doesNotContain(".kitchenboard,\n.hallboard {");

        assertThat(block(css, ".kitchenboard .board {"))
                .as("盤面側に上限を持たせない。幅は本文の左右余白で調整する")
                .doesNotContain("max-width");
    }

    @Test
    @DisplayName("★ 本文は 1920px で止めて中央に置く（Render と同じ作り）")
    void widthIsCappedLikeRender() throws Exception {
        String css = css();

        // 実測（dashboard.render.com を開いて computed style を読んだ値）:
        //   main { max-width: 1920px; margin-inline: auto; padding-inline: 48px }
        //   帯 56px ／ ブロックの上下 48px（my-12）
        //
        // 上限を盤面ではなく本文に置くこと。盤面だけを寄せると、
        // 見出しの帯・数字カード・盤面の左端がばらばらになります（35-5 の注記）。
        // ★ ".theme-desk .staff-main {" で探さないこと。
        //   同じセレクタは 30 節（基準）と 32 節（1440px の帯）の 2 か所にあり、
        //   単純な indexOf だと帯のほうを拾って必ず落ちます。
        //   基準の 1 本は --main-pad-x: 24px を持っているので、そこを目印にします
        int at = css.indexOf("--main-pad-x: 24px;");
        assertThat(at).as("30 節の基準となる本文の指定が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("max-width: 1920px;");
        assertThat(rule).contains("margin-inline: auto;");

        // 1920px の窓では効きません（サイドバー 300 を引いた 1620px が上限に届かない）。
        // 効きはじめるのは本文が 1920px を超える 2900px 級からです
        assertThat(css).contains("--main-pad-x: clamp(40px, calc(34px + (100vw - 1440px) * 0.1125), 160px);");
    }

    @Test
    @DisplayName("★ 広い画面では盤面の上下も 48px（Render と同じ。設計の 32px は据え置き）")
    void boardScreensGetVerticalRoomOnWideScreens() throws Exception {
        String css = css();

        // 厨房・ホール・品切れ・食材は、設計どおり上下 32px にしてあります
        // （.theme-desk .staff-main:has(.kitchenboard) など、詳細度 0,3,0）。
        // 1432px ではそれでよいのですが、3807px では左右が 930px 空くのに
        // 上下が 32px のままで、枠として成り立ちません。
        // Render も同じ形（左右 833px・上下 48px）なので、そこにそろえます。
        int at = css.indexOf("@media (min-width: 1440px)");
        assertThat(at).as("32 節の 1440px の段が無い").isGreaterThan(0);
        String wide = css.substring(at);

        assertThat(wide).contains(".theme-desk .staff-main:has(.kitchenboard),");
        assertThat(wide).contains("--main-pad-y: 48px;");

        // ★ 1440px 未満は触らないこと。タブレットは逆に「詰めて枚数を増やす」
        //   方向に直したばかりで、ここを一緒に広げると打ち消します
        String narrow = css.substring(0, at);
        assertThat(narrow).contains(".theme-desk .staff-main:has(.kitchenboard) { --main-pad-y: 32px; }");
    }

    @Test
    @DisplayName("★ ガターは 1920px で 88px（Figma PC01 の実測値）に乗る一次式")
    void gutterGrowsWithTheViewport() throws Exception {
        String css = css();

        // (1920, 88) と (2560, 160) を通る直線。傾き 72 / 640 = 0.1125
        //   1440px → 40px（下限）／1920px → 88px／2400px → 142px／2560px → 160px（上限）
        assertThat(css).contains(
                "--main-pad-x: clamp(40px, calc(34px + (100vw - 1440px) * 0.1125), 160px);");

        // 2000px 以上の段は縦だけ持つ。左右を再指定すると上の clamp を殺してしまう。
        // ★ ここで block() は使えない。@media は最初の } で終わらないので、
        //   内側の 1 本目（.staff-shell）だけを見て素通りしてしまう。
        //   32 節はファイルの最後と決めてあるので、そこから末尾までを丸ごと見る
        String bare = cssWithoutComments();
        int at = bare.indexOf("@media (min-width: 2000px)");
        assertThat(at).as("32 節の 2000px の段が無い").isGreaterThan(0);
        assertThat(bare.substring(at)).doesNotContain("--main-pad-x");
    }

    @Test
    @DisplayName("★ ホールの列も伸びる（360px 固定のままだと厨房とちぐはぐになる）")
    void hallLanesStretchLikeTheKitchen() throws Exception {
        String css = css();

        assertThat(css).contains(".kitchenboard .board { grid-template-columns: repeat(3, minmax(0, 1fr)); }");

        // 35-2 のホール側。厨房の 1 行と混ざらないよう、説明の位置から後ろだけを見る
        int at = css.indexOf("/* ホール。列は厨房と同じく伸ばす");
        assertThat(at).as("35-2 のホール側の説明が無い").isGreaterThan(0);
        String hall = css.substring(at, Math.min(css.length(), at + 1200));
        assertThat(hall).contains(".hallboard .board { grid-template-columns: repeat(3, minmax(0, 1fr)); }");
        assertThat(hall).contains(".hallboard .grid--3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }");
    }

    @Test
    @DisplayName("★ ホールの土台にも 1120px の上限を残さない（後ろで列を伸ばしても効かなくなる）")
    void hallBaseRulesDoNotCapTheWidth() throws Exception {
        String css = css();

        // 15 節側の土台。ここに max-width が残っていると、
        // 35 節で grid-template-columns を 1fr にしても幅が 1120px で止まる
        String base = block(css, ".theme-desk .hallboard .board,\n.hallboard .board {");
        assertThat(base).doesNotContain("max-width");

        String cards = block(css, ".theme-desk .hallboard .grid--3,\n.hallboard .grid--3 {");
        assertThat(cards).doesNotContain("max-width");
    }
}
