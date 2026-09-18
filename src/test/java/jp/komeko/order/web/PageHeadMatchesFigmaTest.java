package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画面の見出し帯を Figma の部品「見出し/ページ」（24:1392）にそろえる（2026-09-13）。
 *
 * <p><b>部品の中身（Figma 実測）</b>
 * <pre>
 *   横並び・中央ぞろえ・gap 16 ／ padding 16px 40px ／ 地は白
 *     大見出し 32px Bold  #1c1c1c
 *     補足     14px       #828282     … 出す／出さないを切り替えられる
 *     ボタン   高さ 48px  角丸 4px     … 出す／出さないを切り替えられる
 * </pre>
 *
 * <p><b>見つかった差（1432px で全 16 画面を採寸）</b>
 * <ol>
 *   <li>卓・QRコード・注文履歴・レシピ・原価表 の 4 画面が
 *       {@code .section-title}（padding 0、帯の高さ 45px）のままだった。
 *       設計は 77〜80px なので、帯としてほとんど見えていない</li>
 *   <li>ホール・会計だけ padding が 20px（帯 88px）。設計は 16px（帯 80px）</li>
 *   <li>ボタンの置き場所。<b>2026-09-16 に「右端」で確定しました（店主の判断）。</b>
 *       いったん「補足のすぐ右（左詰め）」に倒して 5 画面を固定しましたが、
 *       その根拠は<b>旧 01 ページ</b>で、現行 07 ページはほぼ全画面が右端でした。
 *       経緯は {@link #theButtonsSitAtTheRightEdge()} に書いてあります</li>
 * </ol>
 *
 * <p><b>ここで直さないもの（店主に確認するまで動かさない）</b>
 * <ul>
 *   <li>題の行間 36px。Figma の部品は 1.4（＝44.8px）で、ボタンの無い帯は 68px ではなく
 *       77px になる。ただし 36px は「Render に合わせて」と言われて実測から入れた値
 *       （{@link RenderAlignedTypeTest}）。どちらも店主の指示なので、勝手に選ばない</li>
 *   <li>卓・QRコードの「追加」ボタン。Figma の 10 卓・11 QRコードは一覧だけの画面で、
 *       追加フォームが本文に無い。ボタンを足すには画面を 2 枚に割る必要があり、
 *       これは寸法ではなく画面構成の話になる</li>
 * </ul>
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("見出し帯は Figma の「見出し/ページ」どおり")
class PageHeadMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path TPL = Path.of("src/main/resources/templates");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    private String tpl(String name) throws Exception {
        return read(TPL.resolve(name));
    }

    /** 本文（{@code <main>} の中）だけを切り出す。共通レイアウトの帯を数えないため。 */
    private String main(String html) {
        int at = html.indexOf("<main");
        assertThat(at).as("<main> が無い").isGreaterThan(0);
        return html.substring(at, html.lastIndexOf("</main>"));
    }

    /**
     * コメントを落とした HTML。<b>doesNotContain は必ずこちらに掛けること。</b>
     *
     * <p>このプロジェクトは「なぜそう書くか」を注意書きに残す方針なので、
     * 禁止したい文字列がそのまま注意書きに出てきます。
     * 実際このテストも「のばす（.page-head__spacer）で右端へ飛ばさないこと」という
     * 注意書きに一致して落ちました。app.css 側のテスト（WideScreenGutterTest、
     * DeviceStepDownTest）が踏んだのと同じ罠です。
     */
    private String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- 1

    @Test
    @DisplayName("★ 4 画面の h1 を見出し帯に入れる（卓・QR・注文履歴・レシピ）")
    void theFourFlatScreensGetTheBand() throws Exception {
        String[][] screens = {
                // ★ 卓は 2026-09-14 に一覧と編集へ分割した（TableQrScreenSplitTest）。
                // 読む画面（table-list）が「卓」、編集画面が「卓を 編集・追加」
                {"admin/table-list.html", "卓"},
                {"admin/qr.html", "QRコード"},
                {"admin/orders.html", "注文履歴"},
                {"inventory/recipes.html", "レシピ・原価表"},
        };
        for (String[] s : screens) {
            String m = withoutComments(main(tpl(s[0])));
            assertThat(m)
                    .as(s[0] + " の見出しが帯になっていない")
                    .contains("<h1 class=\"page-head__title\">" + s[1] + "</h1>");
            // h1 に .section-title__text が残っていないこと。
            // ★ .section-title そのものは残る。節の見出し（h2）は Figma でも別部品
            assertThat(m)
                    .as(s[0] + " に h1 の section-title__text が残っている")
                    .doesNotContain("<h1 class=\"section-title__text\">");
        }
    }

    @Test
    @DisplayName("★ 補足は残す（件数・日付は帯の中の情報）")
    void theSubtitleSurvivesTheMove() throws Exception {
        assertThat(main(tpl("admin/table-list.html"))).contains("page-head__sub");
        assertThat(main(tpl("admin/qr.html"))).contains("page-head__sub");
        assertThat(main(tpl("admin/orders.html"))).contains("page-head__sub");
        assertThat(main(tpl("inventory/recipes.html"))).contains("page-head__sub");
    }

    // ---------------------------------------------------------------- 2

    /**
     * ★ ホールの帯も 16px（2026-09-18・店主の指示「全体に合わせて欲しい」）。
     *
     * <p><b>ここは二転三転しました。記録を残します。</b>
     *
     * <pre>
     *   2026-09-13  20 → 16 に下げた   01 ページの共通部品（16/40）を見て
     *   2026-09-13  16 → 20 に戻した   現02 ホール・会計 542:3710 が 20/40 だった。
     *                                   01 ページのほうが古い版だと分かったため
     *   2026-09-18  20 → 16            店主が全画面を見くらべて「ズレて見える」
     * </pre>
     *
     * <p>20px だと、この画面だけ題が 4px 下にありました。
     *
     * <pre>
     *   他の 16 画面   器 32 ＋ 行 16 ＋ 字の余り 0 ＝ 上から 48px
     *   ホール・会計   器 32 ＋ 行 20 ＋ 字の余り 0 ＝ 上から 52px
     * </pre>
     *
     * <p>「立って見る画面だけ帯を厚くする」という設計の意図はありましたが、
     * <b>4px の厚みより、どの画面でも題が同じ高さにあること</b>を店主が採りました。
     * 厚さで区別したくなったら、帯ではなく字の大きさで差を付けること。
     *
     * <p>同じ落とし穴が厨房（現01）と品切れ（現03）にもあります。
     * 立って使う 3 画面の寸法を 01 ページから読まないこと、という注意は残ります。
     */
    @Test
    @DisplayName("★ ホールの帯も 16px（全画面で題の高さをそろえる）")
    void theHallBandKeepsItsOwnPadding() throws Exception {
        assertThat(read(CSS))
                .as("ホールだけ 20px に戻っている。この画面だけ題が 4px 下がる")
                .contains(".hallboard .section-title:has(h1) { padding: 16px 40px; }");
    }

    // ---------------------------------------------------------------- 3

    /**
     * ★ ここは 2026-09-16 に裏返しました（店主の判断）。
     *
     * <p>もとは「ボタンは補足のすぐ右（左詰め）。のばすで右端へ飛ばさないこと」でした。
     * <b>その根拠は旧 01 ページの設計です。</b>
     * 現行 07 ページを全画面あたったところ、商品・カテゴリ・スタッフ・バックアップ・
     * 食材・卓・QR・レシピ…と、ほぼ全画面がボタンを右端に置いていました
     * （2026-09-14 の差分チェック「横断の発見 A」）。
     * 古い版だけを見て 5 画面を左詰めに固定していたことになります。
     *
     * <p><b>左端は戻るの居場所です。</b>09-15 に「左＝戻る／右＝実行」と決めたので
     * （{@code BackButtonOnTheLeftTest}）、実行のボタンが右端に行くのは
     * そのルールとも噛み合います。帯の中は 戻る → 題 → 補足 → のばす → 実行 の順。
     *
     * <p>売上（14）の のばす だけは別物なので、{@link #theSalesSpacerStays()} で
     * 別に見ています。あちらは月の帯を左に寄せるためのものです。
     *
     * <p><b>2026-09-18 に 2 点ゆるめました（店主の指示で帯を題の行へ入れたため）。</b>
     * <ul>
     *   <li>日付・月の帯（{@code .datenav}）は数える前に外します。帯の中の
     *       「← 前月」もボタンなので、外さないと<b>いちばん前のボタンが帯のもの</b>に
     *       なり、「のばす より前にボタンがある」と誤判定します。
     *       ここが見張っているのは<b>実行のボタン</b>の位置です</li>
     *   <li>仕入れ・経費は補足（{@code page-head__sub}）を持ちません。もとは
     *       「2026年08月」でしたが、すぐ隣の入力欄に同じ月が出るようになったので
     *       外しました。注文履歴の補足は曜日と件数を足しているので残っています</li>
     * </ul>
     */
    @Test
    @DisplayName("★ ボタンは帯の右端（のばすで送る）")
    void theButtonsSitAtTheRightEdge() throws Exception {
        String[] screens = {
                "admin/items.html",
                "admin/category-list.html",
                "admin/staff-list.html",
                "admin/backups.html",
                "inventory/purchases.html",
        };
        for (String s : screens) {
            String m = withoutComments(main(tpl(s)));
            int head = m.indexOf("class=\"page-head\"");
            assertThat(head).as(s + " に見出し帯が無い").isGreaterThanOrEqualTo(0);
            int end = m.indexOf("</div>", head);
            String band = m.substring(head, end)
                    .replaceAll("(?s)<form[^>]*class=\"datenav\".*?</form>", "");

            int spacer = band.indexOf("page-head__spacer");
            assertThat(spacer).as(s + " に のばす（page-head__spacer）が無い").isGreaterThan(0);

            int sub = band.indexOf("page-head__sub");
            if (sub > 0) {
                assertThat(spacer).as(s + " の のばす が補足より前にある").isGreaterThan(sub);
            }

            // ボタンが 2 つある画面（仕入れ・経費）もあるので、いちばん前のものを見る。
            // のばす がその前にあれば、まとめて右端へ送られる。
            int btn = band.indexOf("class=\"btn");
            assertThat(btn).as(s + " にボタンが無い").isGreaterThan(0);
            assertThat(spacer)
                    .as(s + " の のばす がボタンより後ろにある（右端へ送れていない）")
                    .isLessThan(btn);
        }
    }

    @Test
    @DisplayName("★ 売上の のばす は残す（月ナビを左に寄せるためのもの・14 売上）")
    void theSalesSpacerStays() throws Exception {
        String m = main(tpl("admin/sales.html"));
        int nav = m.indexOf("monthnav");
        int spacer = m.indexOf("page-head__spacer");
        assertThat(nav).isGreaterThan(0);
        assertThat(spacer).as("のばすは月ナビの後ろ").isGreaterThan(nav);
    }

    // ---------------------------------------------------------------- 部品

    /**
     * 節の見出し（h2）も Figma に部品があります（見出し/節 24:1397）。
     * <pre>
     *   横並び・中央ぞろえ・gap 16 ／ padding 16px 40px ／ 帯 68px
     *     中見出し 24px Bold  #1c1c1c
     *     補足     14px       #828282
     * </pre>
     *
     * <p>文字の大きさ（24px）は合っていましたが、<b>左右の 40px が無く</b>、
     * ページの見出しより 40px 外側から始まっていました。
     * 同じ画面の中で見出しの左端が 2 つあることになるので、そこをそろえます。
     *
     * <p>立って使う 3 画面（厨房・ホール・品切れ）は対象外。
     * 現02・現03 の節見出しは 20px・左右 0 で、そちらが正です。
     */
    @Test
    @DisplayName("★ 節の見出しも 16/40 の帯にする（机で読む画面だけ）")
    void theSectionBandMatchesItsComponent() throws Exception {
        String css = read(CSS);
        String sel = ".theme-desk .staff-main > main:not(.kitchenboard):not(.hallboard):not(.soldoutpage)"
                + " > .section-title:has(h2)";
        assertThat(css).contains(sel + " { padding: 16px 40px; }");

        // iPad でも帯は帯。B案の 12/24 に乗せないと、
        // ページの見出しだけ 24px に寄って節の見出しが 40px に取り残される
        assertThat(css).contains(sel + " { padding: 12px 24px; }");
    }

    @Test
    @DisplayName("★ 帯そのものの寸法は Figma の部品どおり（16/40・gap 16）")
    void theBandItselfMatchesTheComponent() throws Exception {
        String css = read(CSS);
        int at = css.indexOf(".page-head {");
        assertThat(at).as(".page-head が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("padding: 16px 40px;");
        assertThat(rule).contains("gap: 16px;");
        assertThat(rule).contains("align-items: center;");
    }
}
