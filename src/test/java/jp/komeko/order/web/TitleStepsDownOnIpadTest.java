package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iPad では題も一段下げる（2026-09-18、店主の指摘）。
 *
 * <pre>
 *   「iPad の食材・在庫、品切れ・残数管理、税理士の見出しのフォントが大きいけど、
 *     これレスポンス対応できてないよね」
 * </pre>
 *
 * <p><b>そのとおりでした。</b>端末の段差（{@code @media (max-width: 1380px)}）で
 * 題を 32 → 28px に落としていますが、<b>落としている相手が
 * {@code .page-head__title} だけ</b>でした。
 *
 * <p>題を {@code .section-title__text} で出している画面は、一段下げの帯に
 * 引っかかりません。落ちる場所が別にあるにはあるのですが、そちらは
 * <b>960px 以下</b>です。
 *
 * <pre>
 *   1380px 以下 … .page-head__title        32 → 28   ← 15 枚はここで下がる
 *    960px 以下 … h1.section-title__text   32 → 24   ← 8 枚はここまで下がらない
 *
 *   iPad は 1024px。ちょうど 2 つの帯の<b>あいだ</b>に落ちる。
 * </pre>
 *
 * <p>実測（1432px と 1024px で全 22 画面）で、取り残されていたのは 8 枚。
 * ホール・会計／品切れ・残数／食材・在庫／税理士 5 枚です。
 * どれも題が {@code .section-title__text} でした。
 *
 * <p><b>ホール・会計も含めます。</b>店主が挙げたのは 3 種類ですが、同じ仕組みの
 * 取りこぼしです。立って使う画面を小さく組まない決まり（CLAUDE.md）はありますが、
 * <b>厨房ボードは前から一段下がっています</b>（{@code .kitchenboard .griddle h1}）。
 * 立って使う 2 画面で動きが違うほうが説明できません。
 */
@DisplayName("iPad では題も一段下げる")
class TitleStepsDownOnIpadTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * 40 節（1380px の段差）の帯だけを切り出す。
     *
     * <p>コメントを落とすこと。この app.css は「ここに書かないこと」という
     * 注意書きを残す方針なので、{@code doesNotContain} が注意書きに一致します
     * （{@code DeviceStepDownTest} が踏んだのと同じ罠）。
     */
    private String band() throws Exception {
        String css = css();
        int at = css.indexOf("40. 端末の段差");
        assertThat(at).as("40 節が無い").isGreaterThan(0);
        int media = css.indexOf("@media (max-width: 1380px)", at);
        assertThat(media).as("40 節の media が無い").isGreaterThan(at);
        return css.substring(media, css.indexOf("\n}", media))
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * ★ {@code .section-title__text} の題も 28px に落とす。
     *
     * <p>{@code h1} を付けて書くこと。{@code .theme-desk h1.section-title__text}
     * （詳細度 0,2,1）に、クラス単独（0,1,0）では負けます。
     * 同じ罠を {@code .page-head__title} で 1 度踏んでいます。
     */
    @Test
    @DisplayName("★ 題が .section-title__text の画面も 1380px で 28px に落ちる")
    void theSectionTitleAlsoStepsDown() throws Exception {
        String b = band();

        assertThat(b)
                .as("一段下げの帯に .section-title__text が無い。"
                        + "食材・在庫・品切れ・残数・税理士の題が iPad で 32px のまま残る")
                .contains("h1.section-title__text");
        assertThat(b)
                .as("28px / 行間 32px に落としていない（.page-head__title と同じ値にそろえる）")
                .containsPattern("h1\\.section-title__text \\{ font-size: 28px; line-height: 32px; \\}");
    }

    /**
     * ★ 詳細度で負けないこと。
     *
     * <p>{@code .theme-desk h1.section-title__text { font-size: 32px }}（0,2,1）が
     * 30 節にあります。帯の側も同じ詳細度以上で書かないと、読めるのに効かない 1 行になります。
     */
    @Test
    @DisplayName("★ .theme-desk を付けて書く（30 節の 32px に負けない）")
    void theRuleIsSpecificEnough() throws Exception {
        String b = band();

        assertThat(b)
                .as(".theme-desk が付いていない。30 節の "
                        + ".theme-desk h1.section-title__text（0,2,1）に負ける")
                .contains(".theme-desk h1.section-title__text");
    }

    /**
     * ★ 品切れ・残数だけは、画面固有の指定も落とすこと。
     *
     * <p>あそこは {@code .soldoutpage .soldout-head .section-title__text}（0,3,0）で
     * 32px を持っています。上の共通の行は（0,2,1）なので、<b>クラスの数が
     * 3 対 2 で負けます</b>。後ろに置いても勝てません。
     *
     * <p>実測で「8 枚中 7 枚だけ直っている」のを見つけて足しました。
     * 帯に 1 行足しただけで全部直った気になると、ここを落とします。
     */
    @Test
    @DisplayName("★ 品切れ・残数の題も落ちる（画面固有の 0,3,0 に勝つ）")
    void theSoldoutTitleAlsoStepsDown() throws Exception {
        assertThat(band())
                .as("品切れ・残数の題が落ちない。あの画面は 0,3,0 で 32px を持っていて、"
                        + "共通の 0,2,1 では負ける")
                .contains(".soldoutpage .soldout-head .section-title__text "
                        + "{ font-size: 28px; line-height: 32px; }");
    }

    /**
     * ★ 帯が 30 節より後ろにあること。
     *
     * <p>同じ詳細度なら後ろが勝ちます。前に置くと、書いてあるのに効きません。
     */
    @Test
    @DisplayName("★ 帯は 30 節より後ろ（同詳細度の後勝ち）")
    void theBandComesAfterTheDeskSection() throws Exception {
        String css = css();

        int desk = css.indexOf(".theme-desk h1.section-title__text { font-size: 32px");
        int band = css.indexOf("@media (max-width: 1380px)", css.indexOf("40. 端末の段差"));

        assertThat(desk).as("30 節の 32px が無い").isGreaterThan(0);
        assertThat(band).as("40 節の帯が無い").isGreaterThan(0);
        assertThat(band)
                .as("帯が 30 節より前にある。同じ詳細度なので、書いてあるのに効かない")
                .isGreaterThan(desk);
    }

    /**
     * ★ 960px の帯はそのまま残すこと。
     *
     * <p>サイドバーが 64px の帯になり、本文の幅が一気に減る幅です。
     * そこでは 24px までもう一段落とします。1380px の帯を足したからといって、
     * こちらを消さないこと。
     */
    @Test
    @DisplayName("★ 960px でさらに 24px へ落とす段は残っている")
    void theNarrowBandStillGoesToTwentyFour() throws Exception {
        assertThat(css())
                .as("960px の段が消えている。サイドバーが畳まれる幅で題が長いと 3 行になる")
                .containsPattern("(?s)@media \\(max-width: 960px\\).*?"
                        + "\\.theme-desk h1\\.section-title__text \\{ font-size: 24px; \\}");
    }
}
