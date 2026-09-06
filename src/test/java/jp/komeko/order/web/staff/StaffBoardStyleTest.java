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
    @DisplayName("★ 席の帯は高さ 72・幅いっぱい（設計 暗/席の帯）")
    void seatBarFillsTheWidth() throws Exception {
        String css = Files.readString(APP_CSS);
        String rule = rule(".staffbar {");

        // ★ width:100% が無いと、flex コンテナが中身の幅まで縮む。
        //   実測で 390 → 253px になり、3 つの文字が中央に寄った短い帯になった。
        //   すぐ下のタブ帯（.tabbar）が同じ理由で同じ指定を持っている
        assertThat(rule).as("帯が中身の幅に縮む").contains("width: 100%;");

        // 43px だと下の大分類タブ（102px）に対して細すぎて「画面の一部」に見える。
        // 店員が見失ってはいけない情報なので、帯として成立する高さにする
        assertThat(css).as("帯の高さが設計と違う").contains(":root { --staffbar-h: 72px; }");

        // ★ 高さは変数から引くこと。ここに 72px と直接書くと、
        //   下の stickyTopCountsTheSeatBar が守っている足し算が
        //   帯の実際の高さと無関係になる（変えても向こうが気づかない）
        assertThat(rule).as("高さを直接書いている（変数から引くこと）")
                .contains("min-height: calc(var(--staffbar-h) + env(safe-area-inset-top, 0px));");
    }

    @Test
    @DisplayName("★ カテゴリの帯が席の帯のぶん下がる（大分類タブに重ならない）")
    void stickyTopCountsTheSeatBar() throws Exception {
        // カテゴリの帯（.tabs）は position:fixed で、上にいる帯の高さの合計を
        // --sticky-top として受け取って自分の位置を決める。
        // 帯が 1 本増えたのに足し忘れると、その高さぶん上にずれて
        // 「お食事／ドリンク／サービス／伝票・会計」に重なる。
        //
        // 2026-09-06 に実際そうなった（席の帯 72px を足しておらず、
        // 大分類タブ 72〜174 の上に .tabs が 102 から乗った）。
        // 画面は落ちず、文字が帯の裏に隠れるだけなので、
        // 実機を並べて見るまで誰も気づけない。
        String rule = rule(".page--seated:has(.staffbar) {");

        assertThat(rule).as("席の帯を数えていない").contains("var(--staffbar-h)");
        assertThat(rule).as("大分類タブを数えていない")
                .contains("var(--tabbar-top)")
                .contains("var(--tabbar-item-h)");

        // ノッチのぶんは 1 回だけ。いちばん上の 1 本（席の帯）が空ければ足りる。
        // 2 回足すと、その高さぶん下に隙間ができる
        assertThat(rule.split("env\\(safe-area-inset-top", -1).length - 1)
                .as("ノッチのぶんを重ねて数えている").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 変数の定義は 1 か所ずつ（2 か所に書くと片方が取り残される）")
    void barHeightsAreDefinedOnce() throws Exception {
        String css = Files.readString(APP_CSS);

        // 高さの出どころが 2 か所あると、片方だけ直したときに
        // 数える側（--sticky-top）が古い値のまま残り、黙って重なる。
        // 上の 2 つのテストは「足し算が書いてあること」しか見ないので、
        // 材料が二重定義になっていないかはここで守る
        assertThat(css.split("--staffbar-h:", -1).length - 1)
                .as("--staffbar-h が 2 か所以上で定義されている").isEqualTo(1);
        assertThat(css.split("--tabbar-item-h:", -1).length - 1)
                .as("--tabbar-item-h が 2 か所以上で定義されている").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 番号は左右の文字より一段大きい（目が先にここへ行く）")
    void seatNameIsLargerThanTheSideLabels() throws Exception {
        // 帯の中でいちばん大事なのは番号。ここを読み違えると別の卓に送ることになる
        assertThat(rule(".staffbar__seat {")).contains("font-size: 16px;");
        // 左右（戻る・ログアウト）は 13px のまま。帯そのものが 13px で、
        // 番号だけが上書きしている
        assertThat(rule(".staffbar {")).contains("font-size: 13px;");
    }

    @Test
    @DisplayName("画面の上下は 40px、見出しの左右は 8px")
    void outerSpacing() throws Exception {
        assertThat(rule(".staff-order {")).contains("padding-block: 40px");
        // マスは内側に 16px 持つので、見出しの文字がマスの中の文字と近い位置で始まる
        assertThat(rule(".staff-order__head {")).contains("padding-inline: 8px;");
    }
}
