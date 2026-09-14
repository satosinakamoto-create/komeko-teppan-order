package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端末の段差ルール（2026-09-13、店主とB案で合意）。
 *
 * <p><b>ルール本文</b><br>
 * 1432（設計幅）を基準に、iPad の帯（1380px 以下）では
 * <b>大きい文字だけ一段落とし、器の余白も一段詰める</b>。
 * PC（1440px 以上）は文字を据え置き、左右のガターだけ広げる（32 節）。
 * <pre>
 *   文字（一段だけ）   題 32→28 ／ 箱の題 20→18 ／ 数値カードの値 32→28 ／ ページ副題 16→14
 *   床（触らない）     本文・表セル 16 ／ ボタン 15 ／ 列見出し 12 ／ タップ 48
 *   余白              本文の上下 64→48 ／ ブロックの間 48→32 ／ カード内 20→16 ／ 帯 16/40→12/24
 *   据え置き           表の行 68/64 ／ 左右 24 ／ 罫線
 * </pre>
 *
 * <p><b>なぜ 1380px か。</b>盤面のサイドバー畳みと同じ境目です。
 * 「サイドバーが畳まれる幅＝タブレットの帯」という 1 本の線に、寸法の段差もそろえます。
 *
 * <p><b>順番の罠（このテストが順番まで見る理由）</b><br>
 * .dashpage .page-head__sub（0,2,0・38-2 の 16px）などと同じ詳細度の上書きを
 * この帯に書くので、<b>帯は 38・39 節より後ろに置かないと負けます</b>。
 * 実際、35 節（1380px の畳み）に置くと dashpage 側が勝って副題が 16px のままです。
 */
@DisplayName("端末の段差ルール（iPad は一段下げる）")
class DeviceStepDownTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 40 節（1380px の段差）の帯だけを切り出す。 */
    private String band() throws Exception {
        String css = css();
        int at = css.indexOf("40. 端末の段差");
        assertThat(at).as("40 節が無い").isGreaterThan(0);
        int media = css.indexOf("@media (max-width: 1380px)", at);
        assertThat(media).as("40 節の media が無い").isGreaterThan(at);
        // ★ コメントを落としてから返すこと。この app.css は「ここに書かないこと」と
        //   注意書きを残す方針なので、doesNotContain が注意書きに一致して落ちる。
        //   TopbarDesignTest・WideScreenGutterTest が踏んだのと同じ罠
        return css.substring(media, css.indexOf("\n}", media))
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ 大きい文字だけ一段下げる（題 28・箱の題 18・値 28・副題 14）")
    void bigTypeStepsDownOnce() throws Exception {
        String b = band();
        // ★ h1 付き（0,1,1）で書くこと。.theme-desk h1 の 32px（30 節・0,1,1）に
        //   クラス単独（0,1,0）では負ける。題だけ縮まない形で 1 回踏んだ
        assertThat(b).contains("h1.page-head__title,\n  .page-head__title { font-size: 28px; line-height: 32px; }");
        assertThat(b).contains(".kitchenboard .griddle h1 { font-size: 28px; line-height: 32px; }");
        assertThat(b).contains(".panel__title { font-size: 18px; line-height: 24px; }");
        assertThat(b).contains(".statcard__value { font-size: 28px; line-height: 34px; }");
        assertThat(b).contains(".dashpage .page-head__sub { font-size: 14px; }");

        // 床は触らない。本文 16・ボタン 15・列見出し 12 の変更がこの帯に無いこと
        assertThat(b).doesNotContain("font-size: 15px");
        assertThat(b).doesNotContain("font-size: 12px");
    }

    @Test
    @DisplayName("★ 余白も一段詰める（上下 48・ブロック間 32・カード内 16・帯 12/24）")
    void spacingStepsDownOnce() throws Exception {
        String b = band();
        // ★ 2026-09-13：上下余白はこの帯で触らなくなった。
        //   基準が 64 → 32px に反転したので、48px に「詰める」と逆に広がる
        assertThat(b).doesNotContain("--main-pad-y");

        // ★ ブロックの間（48→32）も、同じ日に 41 節へ移しました。
        //   41 節は本文そのものを縦オートレイアウトにするので、
        //   セレクタが .theme-desk .staff-main > main（詳細度 0,2,1）になります。
        //   ここに .dashpage { gap: 32px }（0,1,0）を残すと、
        //   41 節が後ろにあるぶん常に負けて、読めるのに効かない 1 行になります。
        //   移した先は BlockGapMatchesFigmaTest が見ています
        assertThat(b).doesNotContain("gap: 32px");
        assertThat(b).contains("padding: 15px 16px;");   // カード（枠 1px を返して 16）
        assertThat(b).contains(".panel { padding: 16px; }");
        assertThat(b).contains(".page-head { padding: 12px 24px; }");
        // 売上の帯は月ナビ（基準 68px）が入って背が決まる。帯の余白だけ 12 に
        // 落としても 12+68+12=92px にしかならず、iPad で間延びして見えた
        // （2026-09-14、店主「iPad は 80px とかにした方が使いやすい」）。
        // 月ナビを 56px に落として 12+56+12=80px。Figma トi18 も 80 に直し済み
        assertThat(b).contains(".salespage .monthnav { height: 56px; }");
        // 仕入れの大きいカード（202px 設計）はこの詰めの対象外
        assertThat(b).contains(":not(.statcard--tall)");
    }

    @Test
    @DisplayName("★ 帯は 38・39 節より後ろ（同詳細度の後勝ちで一段下げが効く）")
    void theBandComesAfterThePageSections() throws Exception {
        String css = css();
        int band = css.indexOf("40. 端末の段差");
        int dash = css.indexOf(".dashpage .page-head__sub { font-size: 16px;");
        int sales = css.indexOf(".salespage { background: #ffffff; }");
        assertThat(dash).as("38-2 が無い").isGreaterThan(0);
        assertThat(sales).as("39 節が無い").isGreaterThan(0);
        assertThat(band).isGreaterThan(dash);
        assertThat(band).isGreaterThan(sales);
    }
}
