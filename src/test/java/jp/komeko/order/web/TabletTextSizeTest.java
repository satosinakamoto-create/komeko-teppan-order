package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iPad の横（1024px）では文字を縮めない（2026-09-13）。
 *
 * <p><b>なぜ要るのか</b><br>
 * 2026-09-12 の時点で、タブレットの設計は 1 枚もありませんでした。そのため 35-3 は
 * 「1432px の版を保ったまま、入らなくなるものから順に削る」という当座のしのぎで、
 * 1140px 以下で卓名を 20 → 17px、ボタンを 14 → 13px に落としていました。
 *
 * <p>店主から「レスポンス対応で文字が小さくなるのか？」と指摘を受け、
 * Figma に「iPad01 厨房ボード（1024×768）」を起こして確かめました。
 * レーン 293px でも、<b>卓名 20px・明細 15px・ボタン 14px のまま収まります</b>。
 * 入らないのは伝票の頭の 1 行（卓名・番号・同卓・経過時間）だけで、
 * そこは折り返せば済みます。縮める必要はありませんでした。
 *
 * <p><b>なぜ縮めたくないのか</b><br>
 * この 2 画面は、立ったまま離れて一瞬だけ見る画面です。卓名は運び先そのもので、
 * ここを読み違えると料理が違う卓へ行きます。1 画面に入る件数が 1〜2 枚減るほうが、
 * 読み違いより安いと判断しました。
 *
 * <p><b>920px 以下は別の話です。</b>
 * あそこは縦向きのタブレット（768/834px）で、ボタンを横に 2 つ置けません。
 * 縦に積む・明細を 14px にするという寸法は、はみ出しを止めるための物理的な必要から
 * 来ています（35-4 の実測）。ここでは触りません。
 */
@DisplayName("iPad の横（1024px）では文字を縮めない")
class TabletTextSizeTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 35-3 の @media ブロックだけを、コメントを落として切り出す。 */
    private String narrowBandRules() throws Exception {
        String css = css();
        int at = css.indexOf("@media (max-width: 1140px) {");
        assertThat(at).as("35-3 の 1140px の帯が無い").isGreaterThan(0);
        int end = css.indexOf("\n}", at);
        assertThat(end).as("1140px の帯の閉じが見つからない").isGreaterThan(at);
        return css.substring(at, end).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ 卓名は 1024px でも 20px のまま（17px へ落とさない）")
    void tableNameKeepsItsSize() throws Exception {
        assertThat(narrowBandRules())
                .as("卓名は運び先そのもの。ここを縮めると読み違いが料理の誤配になる")
                .doesNotContain(".kitchenboard .ticket__num");
    }

    @Test
    @DisplayName("★ ボタンの文字も 14px のまま（13px へ落とさない）")
    void buttonsKeepTheirSize() throws Exception {
        String band = narrowBandRules();

        // 詰めてよいのは余白だけ。文字には触らない
        // （2026-09-13 に min-height: var(--tap) が並びました。宣言は増えてよいので、
        //   1 本まるごとの一致ではなく「余白の指定があること」で見ます）
        assertThat(band).contains(".kitchenboard .ticket__actions .btn { padding: 10px 8px;");
        assertThat(band).contains(".hallboard .billcard .btn { padding-inline: 12px; }");
        assertThat(band)
                .as("CLAUDE.md のボタン 15px 床に対しても、13px は 2 段の違反になる")
                .doesNotContain("font-size: 13px");
    }

    @Test
    @DisplayName("★ 頭の 1 行は折り返す。縮める代わりに高さで受けるのがこの帯の方針")
    void theTicketHeadWrapsInstead() throws Exception {
        assertThat(narrowBandRules()).contains(".kitchenboard .ticket__head { flex-wrap: wrap;");
    }

    @Test
    @DisplayName("★ 縮める代わりに積む。ボタンの縦積みは 1000px から（920px ではない）")
    void buttonsStackFromOneThousand() throws Exception {
        String css = css();

        // 921〜1000px は、ボタンを 13px に縮めることで横に 2 つ置けていた帯。
        // 14px に戻したぶん、ここは積んで受ける（実測 960px で 2px、921px で 15px 溢れた）
        int at = css.indexOf("@media (max-width: 1000px) {");
        assertThat(at).as("35-6 の 1000px の帯が無い").isGreaterThan(0);
        String band = css.substring(at, css.indexOf("\n}", at));

        assertThat(band).contains(".kitchenboard .ticket__actions { flex-direction: column; }");
        // ★ 2026-09-26：ここにあった min-height: 48px は外しました。
        //   ボタンの高さは --btn-h（44px）が全画面で決めます。幅で落とす必要が
        //   そもそも無くなったためで、積む・積まないの判断だけがこの帯に残ります。
        assertThat(band).as("ボタンの高さがこの帯に戻っている。--btn-h に任せること")
                .doesNotContain("min-height");
    }

    @Test
    @DisplayName("★ 3 列をやめる境目は 729px（帯の畳みの 699px とは別物）")
    void boardsDropToOneColumnAtSevenTwentyNine() throws Exception {
        String css = css();

        // 実測（文字を設計の寸法に戻したあと）:
        //     730px 以上 … 厨房・ホールとも溢れなし
        //     720px      … ホール 5 件・最大 3px（伝票カードのボタン）
        //     700px      … 厨房 3 件・最大 4px ／ ホール 5 件・最大 6px
        // 溢れたぶんは .lane の overflow:hidden に無言で切られるので、境目を上げる。
        // 実機は iPad mini の縦 744px と旧 iPad の 768px。どちらも 3 列のまま残る
        int at = css.indexOf("@media (max-width: 729px) {");
        assertThat(at).as("35-4 の 729px の帯が無い").isGreaterThan(0);
        String band = css.substring(at, css.indexOf("\n}", at));
        assertThat(band).contains("grid-template-columns: minmax(0, 1fr);");

        // 上の帯（31 画面共通）を畳むのは別の話。あちらは 430px 級で右端が出る問題で、
        // ここまで一緒に上げると 700px 台で営業日が消える
        int bar = css.indexOf("@media (max-width: 699px) {");
        assertThat(bar).as("上の帯を畳む 699px の帯が無い").isGreaterThan(0);
        String barBand = css.substring(bar, css.indexOf("\n}", bar));
        assertThat(barBand).contains(".topbar__day { display: none; }");
        assertThat(barBand).doesNotContain("grid-template-columns");
    }

    @Test
    @DisplayName("★ 920px 以下（縦向き）の明細 14px は据え置き。あちらは行分割の都合")
    void portraitBandIsLeftAlone() throws Exception {
        String css = css();
        int at = css.indexOf("@media (max-width: 920px) {");
        assertThat(at).as("920px の帯が無い").isGreaterThan(0);
        String band = css.substring(at, css.indexOf("\n}", at));

        // 「サッポロ赤星（中瓶）」が 143〜149px の帯で折り返さず切られる問題への対処。
        // 見た目の好みではないので、ここは 20px を守る話とは切り離す
        assertThat(band).contains(".kitchenboard .ticket__items li,");
        assertThat(band).contains(".kitchenboard .ticket__items li:last-child { font-size: 14px; }");
    }
}
