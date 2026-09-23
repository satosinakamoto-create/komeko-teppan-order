package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * お客さまが QR を読んで最初に見る画面（設計 暗00 91:3739）。
 *
 * <p><b>このテストが守っているもの＝「最初の 1 画面」が設計から離れないこと。</b>
 *
 * <p>2026-09-23 に実測したところ、設計と <b>12 か所</b>ちがっていました。
 * どれも目では分からない差で、スクリーンショットを見ているかぎり気づけません。
 *
 * <pre>
 *   段の間      設計 24 ずつ  →  実装 16 / 32 / 8 / 32（mt-4・mt-6 の積み重ね）
 *   本文の上    設計 32       →  実装 0（margin が container から抜けていた）
 *   題          設計 24px     →  実装 16px ＋ きつね色の丸
 *   カード      設計 pad24/16 →  実装 pad16/16
 *   カード1行目 設計 16 #fff  →  実装 15px #9e9e9e
 *   カード2行目 設計 14px     →  実装 13px
 *   来店人数    設計 14px 400 →  実装 13px 500
 *   人数ボタン  設計 gap12    →  実装 gap16（76×4＋16×3＝352 で入れ物 343 を超えていた）
 *   ボタンの枠  設計 #333333  →  実装 #444444（--border-strong を引いていた）
 *   入力欄の地  設計 #232323  →  実装 #202020
 *   入力欄の枠  設計 #333333  →  実装 #444444
 *   決定の枠    設計 なし     →  実装 1px #444444
 * </pre>
 *
 * <p><b>共通部品は動かさないこと。</b>
 * 上の値を {@code .section-title} や {@code .label} 側で直すと、
 * 伝票・カート・メニューを含む 24 画面が巻き添えになります。
 * Figma でも題（24px・丸なし）と節見出し（16px・丸あり＝暗04「お席の伝票」）は
 * 別の部品なので、この画面だけを {@code .startpage} で囲って当てています。
 *
 * <p>Spring を起動しません。見ているのは CSS とテンプレートの中身だけなので、
 * 素の JUnit のほうが速く、落ちたときに原因もそのまま読めます。
 */
@DisplayName("お客さまの入口（暗00）を設計どおりに保つ")
class CustomerEntryScreenTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");
    private static final Path HTML = Path.of("src/main/resources/templates/customer/table-start.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 暗00 の節だけを、コメントを落として切り出す。 */
    private String block() throws Exception {
        String css = css();
        int at = css.indexOf("/* --- お客さまの入口（設計 暗00");
        assertThat(at).as("暗00 の節が app.css から消えている").isGreaterThan(0);
        int end = css.indexOf("/* --- 伝票ページ", at);
        assertThat(end).as("暗00 の節の終わりが見つからない").isGreaterThan(at);
        return css.substring(at, end).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★★ 共通部品には手を入れない（この画面だけ .startpage で囲う）")
    void theSharedPartsAreLeftAlone() throws Exception {
        assertThat(Files.readString(HTML))
                .as("★ .startpage の目印が無い。囲いが無いと上書きが全画面に漏れる")
                .contains("<main class=\"startpage\">");

        String block = block();
        // この節の指定は、ぜんぶ .startpage の内側に閉じていること。
        for (String line : block.split("\n")) {
            String s = line.trim();
            if (!s.endsWith("{") || s.startsWith("@") || s.startsWith("}")) {
                continue;
            }
            assertThat(s)
                    .as("★★ 共通部品に直接当てている（24 画面へ漏れる）: %s", s)
                    .contains(".startpage");
        }
    }

    @Test
    @DisplayName("★ 段の間は 24。mt-4 / mt-6 の積み上げに戻さない")
    void theRhythmIsOneColumnOfTwentyFour() throws Exception {
        String block = block();

        assertThat(block).contains("gap: 24px;");
        assertThat(block).contains("padding-top: 32px;");
        // 段ごとの上下余白を無効にしていないと、gap と二重になって 48px になる
        assertThat(block).contains(".startpage .container > * { margin-top: 0; margin-bottom: 0; }");
        assertThat(block).contains(".startpage form > * { margin: 0; }");
    }

    /**
     * ★ 中身が空の flash を隠すこと。
     *
     * <p>{@code fragments/common :: flash} は、出すものが無くても
     * {@code <div class="stack-sm">} を必ず 1 つ残します。
     * 段組みを flex + gap にした以上、空の入れ物でも 24px のすきまになり、
     * <b>題だけが 24px 下がった画面</b>になります。
     *
     * <p>{@code :empty} では止められません。Thymeleaf が改行とインデントを
     * 残すので、空白テキストがあって一致しないためです。
     */
    @Test
    @DisplayName("★ 空の flash が段を 1 つ食わないようにする")
    void theEmptyFlashSlotDoesNotEatARow() throws Exception {
        String block = block();

        assertThat(block)
                .as("★ 空の flash を隠す指定が無い。題が 24px 下がる")
                .contains(".stack-sm:not(:has(*)) { display: none; }");
        assertThat(block)
                .as("★ :empty は Thymeleaf の改行に負ける")
                .doesNotContain(".stack-sm:empty");
    }

    @Test
    @DisplayName("★ 題は 24px。丸は出さない（丸つき 16px は節見出しの部品）")
    void theTitleIsTwentyFourWithoutTheDot() throws Exception {
        String block = block();

        assertThat(block).contains(".startpage h1.section-title__text { font-size: 24px;");
        assertThat(block).contains(".startpage .section-title::before { display: none; }");
    }

    @Test
    @DisplayName("★ 枠は --border（#333333）。--border-strong に戻さない")
    void theBordersUseTheSofterToken() throws Exception {
        String block = block();

        assertThat(block)
                .as("★ 人数ボタンの枠が --border ではない")
                .contains("border: 1px solid var(--border);");
        assertThat(block)
                .as("★ 入力欄の地は --surface(#232323)。--surface-2 だと一段沈む")
                .contains("background: var(--surface);");
        assertThat(block)
                .as("★★ --border-strong(#444444) は設計に無い色")
                .doesNotContain("--border-strong");
    }

    /**
     * ★ 人数ボタンの間は 12px。
     *
     * <p>16px だと 76×4 ＋ 16×3 ＝ 352 になり、入れ物の 343 をはみ出します
     * （2026-09-23 に実測。ボタンが枠から出ていました）。
     * 12 なら 340 で収まります。<b>見た目では 4px の差は分かりません。</b>
     */
    @Test
    @DisplayName("★ 人数ボタンの間は 12px（16 だと入れ物からはみ出す）")
    void theGuestButtonsFitInTheirContainer() throws Exception {
        assertThat(block()).contains(".startpage .grid--4 { gap: 12px; }");

        int width = 76, gap = 12, columns = 4, container = 343;
        assertThat(width * columns + gap * (columns - 1))
                .as("★ 人数ボタンが 1 行に収まらない")
                .isLessThanOrEqualTo(container);
    }

    @Test
    @DisplayName("★ 決定ボタンに枠は無い。9 名以上の欄は placeholder で案内する")
    void theOtherGuestCountRowMatchesTheDesign() throws Exception {
        assertThat(block())
                .as("★ 決定の枠を消していない")
                .contains(".startpage .guestother__go {");
        assertThat(block()).contains("border: 0;");

        String html = Files.readString(HTML);
        assertThat(html)
                .as("★ 設計は入力欄の中に「9名以上のとき」")
                .contains("placeholder=\"9名以上のとき\"");
        assertThat(html)
                .as("★★ ラベルの要素ごと消さない。placeholder は読み上げられないことがある")
                .contains("<label class=\"visually-hidden\" for=\"guestCountOther\">9名以上のとき</label>");
        assertThat(html)
                .as("★ 設計は「人数」ではなく「来店人数」。9 名以上の欄と区別が付かなくなる")
                .contains(">来店人数<");
    }

    /**
     * ★ 注意書きは青い {@code .alert--info} ではない。
     *
     * <p>設計は面 {@code #232323} に<b>左 4px のきつね色の帯</b>です。
     * この画面で色が付くのは、選んだ人数ボタンと、この帯の 2 つだけ。
     * どちらもきつね色で、お客さま側のアクセントはこの 1 色に絞ってあります。
     */
    @Test
    @DisplayName("★ 注意書きは左 4px のきつね色。青い案内板にしない")
    void theNoticeIsAnAccentBarNotABlueAlert() throws Exception {
        assertThat(block()).contains("border-left: 4px solid #dd9a4e;");
        assertThat(Files.readString(HTML))
                .as("★ 青い .alert--info に戻っている")
                .doesNotContain("class=\"alert alert--info mt-6\"");
    }
}
