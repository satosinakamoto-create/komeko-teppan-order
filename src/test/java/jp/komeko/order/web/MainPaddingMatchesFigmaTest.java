package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本文の上下余白を Figma どおりにする（2026-09-13）。
 *
 * <p><b>何が起きていたか</b><br>
 * Figma 01 ページの全 23 画面を採寸して、いまのコードと突き合わせたところ、
 * 16 画面中 12 画面で本文の上下余白が違っていました。
 * 基準の取り方が逆だったのが原因です。
 * <pre>
 *   コード … 基準 64px。4 画面だけ :has で 32px に落とす
 *   Figma … ほとんどが 32px。64px なのは 厨房・食材在庫・レシピ・バックアップ だけ
 * </pre>
 * そこで基準を 32px に反転し、64px のほうを :has で指定する形にしました。
 * これで 10 画面が一度に Figma どおりになります。
 *
 * <p><b>厨房ボードだけ 32px のまま（Figma が 2 つあるため）</b><br>
 * 01 ページ「02 厨房ボード」は 64px ですが、05 ページ「現01 厨房ボード」は 32px です。
 * 現01 は iPad で伝票が 1.7 枚しか見えない問題を詰めたときの版で、
 * 64px に戻すと 1 枚ぶん見えなくなります。店主の判断待ちのため、
 * いまは 05 ページ（32px）を採っています。
 * 01 ページに合わせるなら、この :has を消せば基準の 32px が当たります……
 * ではなく、64px 側の並びに足してください。
 *
 * <p><b>iPad の一段下げ（40 節）との関係</b><br>
 * 40 節は「基準 64 → iPad 48」という段差でしたが、基準が 32 になったので
 * 詰める意味がなくなりました（32 のほうが既に狭い）。上下の一段下げは外し、
 * 文字と他の余白の段差はそのまま残します。
 */
@DisplayName("本文の上下余白は Figma どおり")
class MainPaddingMatchesFigmaTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 基準は 32px（Figma のほとんどの画面がこれ）")
    void baseIsThirtyTwo() throws Exception {
        assertThat(css()).contains("--main-pad-y: 32px; --main-pad-x: 24px;");
    }

    @Test
    @DisplayName("★ 64px は Figma がそう描いている画面だけ（食材在庫・レシピ・バックアップ）")
    void onlyTheDesignedScreensGetSixtyFour() throws Exception {
        String css = css();
        assertThat(css).contains(".theme-desk .staff-main:has(.inv-ingredients),");
        assertThat(css).contains(".theme-desk .staff-main:has(.recipepage),");
        assertThat(css).contains(".theme-desk .staff-main:has(.backuppage) { --main-pad-y: 64px; }");

        // 32px に落とすための :has はもう要らない（基準がそれ）
        String bare = css.replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(bare).doesNotContain(":has(.hallboard) { --main-pad-y: 32px; }");
        assertThat(bare).doesNotContain(":has(.soldoutpage) { --main-pad-y: 32px; }");
    }

    @Test
    @DisplayName("★ 厨房は 32px のまま（05 ページの現01。01 ページとは値が違う）")
    void theKitchenKeepsThirtyTwo() throws Exception {
        // 基準が 32 になったので、厨房に個別指定は要らない。
        // 64 側の並びにも入っていないことで「32 のまま」を担保する
        String css = css().replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(css).doesNotContain(":has(.kitchenboard) { --main-pad-y: 64px; }");
    }

    @Test
    @DisplayName("★ iPad の一段下げから上下余白を外す（基準 32 のほうが既に狭い）")
    void theTabletStepDownNoLongerTouchesVerticalPadding() throws Exception {
        String css = css();
        int at = css.indexOf("40. 端末の段差");
        assertThat(at).as("40 節が無い").isGreaterThan(0);
        String band = css.substring(at).replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(band).doesNotContain("--main-pad-y");

        // 文字と他の余白の一段下げは残す
        assertThat(band).contains("font-size: 28px; line-height: 32px;");
        assertThat(band).contains(".page-head { padding: 12px 24px; }");
    }

    @Test
    @DisplayName("★ 広い画面でも上下は動かさない（Figma は幅で変えていない）")
    void wideScreensDoNotChangeVerticalPadding() throws Exception {
        String css = css();
        int sec = css.indexOf("32. 広い画面");
        assertThat(sec).isGreaterThan(0);
        assertThat(css.substring(sec).replaceAll("(?s)/\\*.*?\\*/", ""))
                .doesNotContain("--main-pad-y");
    }
}
