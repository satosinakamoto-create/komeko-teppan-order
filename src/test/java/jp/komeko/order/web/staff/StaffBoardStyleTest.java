package jp.komeko.order.web.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番号の盤面（設計 店01 {@code 409:4228}）の寸法を固定する。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 *
 * <p><b>何を守っているか</b><br>
 * この画面は「押す番号を探す」ためだけのものです。
 * 間隔が詰まると番号の塊がひとまとまりに見えず、探すのに目が要ります。
 * 設計と 1〜2 割ずれても画面は壊れないので、並べて見比べないと気づけません。
 */
@DisplayName("番号の盤面の寸法（店01）")
class StaffBoardStyleTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    private String rule(String selector) throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at));
    }

    @Test
    @DisplayName("★ 盤面の行間は 24・列間は 12（縦横を同じにしない）")
    void rowsAreFurtherApartThanColumns() throws Exception {
        // 同じにすると 2×2 の塊に見えて、どれが「横に並んだ 2 つ」なのか分からない。
        // 行間を広げると、左右の 2 つが 1 組として読める
        assertThat(rule(".seat-grid {")).contains("gap: 24px 12px;");
    }

    @Test
    @DisplayName("★ 要素の間隔は 32 で統一（margin でばらつかせない）")
    void spacingComesFromOneGap() throws Exception {
        // 間隔を持つのは .container のほう。
        // .staff-order（main）の直下は .container 1 つだけなので、
        // そちらに gap を付けても効かない
        assertThat(rule(".staff-order > .container {")).contains("gap: 32px;");

        // 個々の要素が margin を持つと、そこだけ間隔が変わる
        assertThat(rule(".staff-order__lead")).contains("margin: 0;");
        assertThat(rule(".staff-order__note")).contains("margin: 0;");
    }

    @Test
    @DisplayName("★ 空のフラッシュ枠が場所を取らない（見出しが 32px 下がらない）")
    void emptyFlashBoxTakesNoSpace() throws Exception {
        String css = Files.readString(APP_CSS);

        // フラッシュの枠は中身が空でも出る（中の 3 つがそれぞれ条件付きのため）。
        // ★ :empty ではなく :not(:has(*))。:empty は改行や字下げの空白も
        //   子として数えるので、Thymeleaf が残す空白に一致しない
        assertThat(css).as("空の枠が gap を 1 つ余分に生む")
                .contains(".staff-order > .container > .stack-sm:not(:has(*)) { display: none; }");
    }

    @Test
    @DisplayName("画面の上下は 40px、見出しの左右は 8px")
    void outerSpacing() throws Exception {
        assertThat(rule(".staff-order {")).contains("padding-block: 40px");
        // マスは内側に 16px 持つので、見出しの文字がマスの中の文字と近い位置で始まる
        assertThat(rule(".staff-order__head {")).contains("padding-inline: 8px;");
    }
}
