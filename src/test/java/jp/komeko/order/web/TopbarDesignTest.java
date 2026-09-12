package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 画面上部の帯（ヘッダー）を設計 287:5547 に固定する（2026-09-13）。
 *
 * <p><b>なぜ要るのか</b><br>
 * この帯は {@code layout/staff.html} にあり、<b>スタッフ側 31 画面すべて</b>に出ます。
 * ところが 2026-09-13 まで、帯を守るテストが 1 本もありませんでした。
 * 消しても、寸法を変えても、色を変えても、テストは全部緑のままです。
 * 31 画面に一度に効く部品でそれは危ないので、設計の値をここで固定します。
 *
 * <p><b>何を守るか</b><br>
 * 寸法（高さ 88／店名 264／ピルの高さ 38・40）と、罫線・補足の色です。
 * 色をわざわざ見ているのは、この 1 日で同じ取り違えを 5 回踏んだためです。
 * {@code var(--border)} は {@code .theme-snow} で {@code rgba(28,28,28,.10)}、
 * {@code var(--text-muted)} は {@code rgba(28,28,28,.55)} に定義されていて、
 * 設計の {@code #e8e8e8} / {@code #828282} とは別物です。白地では近く見えますが、
 * 不透明度で書いた線は端末の表示倍率でさらに薄く丸められ、設計どおりには出ません。
 *
 * <p><b>店名の 264px は「たまたま」ではありません。</b>
 * サイドバーと同じ幅にしてあり、店名ブロックの右端と本文の開始線が一直線にそろいます。
 * ここが崩れると、帯と本文の関係が全画面で狂います。
 *
 * <p>{@code @Transactional} を付けないのは、{@code open-in-view: false} の本番と
 * 同じ形で描画させるためです（{@code HallBoardDesignTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("上の帯（設計 287:5547）")
class TopbarDesignTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path LAYOUT =
            Path.of("src/main/resources/templates/layout/staff.html");

    @Autowired
    private MockMvc mockMvc;

    /** CRLF のまま複数行を contains すると必ず外れるので、読んだ時点で LF にそろえる。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 帯の高さ 88・店名の幅 264（サイドバーと同じ幅で、本文の開始線をそろえる）")
    void frameMetrics() throws Exception {
        String css = css();

        // --topbar-h と --brand-w は .staff-frame の 1 か所で決める。
        // .staff-shell の min-height、.sb の top と height、[id] の scroll-margin-top が
        // すべてこの変数を読んでいるので、ここを直せば全部が追従する
        assertThat(css).contains("--topbar-h: 88px;");
        assertThat(css).contains("--brand-w: 264px;");

        int at = css.indexOf(".topbar {");
        assertThat(at).as(".topbar の指定が無い").isGreaterThan(0);
        String bar = css.substring(at, css.indexOf("}", at));
        assertThat(bar).contains("height: var(--topbar-h);");
        // 左は 0。左端の余白は店名ブロック（264px）の内側に持たせる
        assertThat(bar).contains("padding: 0 20px 0 0;");
        assertThat(bar).contains("background: #ffffff;");

        int brandAt = css.indexOf(".topbar__brand {");
        assertThat(brandAt).as(".topbar__brand の指定が無い").isGreaterThan(0);
        String brand = css.substring(brandAt, css.indexOf("}", brandAt));
        assertThat(brand).contains("width: var(--brand-w);");
        assertThat(brand).contains("height: 56px;");
        assertThat(brand).contains("padding: 0 16px;");
    }

    @Test
    @DisplayName("★ 罫線は #e8e8e8、補足は #828282（var を経由しない）")
    void colorsAreLiteralsNotVariables() throws Exception {
        String css = css();

        assertThat(css).contains("border-bottom: 1px solid #e8e8e8;");
        assertThat(css).contains(".topbar__brand-sub  { display: block; font-size: 11px; color: #828282; }");
        assertThat(css).contains(".topbar__brand-caret { font-size: 12px; color: #828282; flex: none; }");
        assertThat(css).contains(".topbar__day-label { font-size: 13px; color: #828282; }");
        assertThat(css).contains(".topbar__divider { width: 1px; height: 20px; background: #e8e8e8; flex: none; }");

        // 受付ピルとヘルプの丸。宣言ブロックを切り出してから確かめる
        // （素の "border: 1px solid #e8e8e8;" は他の部品にもあるため）
        int stateAt = css.indexOf(".topbar__state {");
        assertThat(stateAt).as(".topbar__state の指定が無い").isGreaterThan(0);
        assertThat(css.substring(stateAt, css.indexOf("}", stateAt)))
                .contains("border: 1px solid #e8e8e8;");

        int helpAt = css.indexOf(".topbar__help {");
        assertThat(helpAt).as(".topbar__help の指定が無い").isGreaterThan(0);
        String help = css.substring(helpAt, css.indexOf("}", helpAt));
        assertThat(help).contains("border: 1px solid #e8e8e8;");
        assertThat(help).contains("color: #828282;");
    }

    @Test
    @DisplayName("★ ピルの高さは枠のぶんを padding から返して 38px / 40px に収める")
    void pillHeightsAccountForTheBorder() throws Exception {
        String css = css();

        // 設計の上下 padding は 10px だが、Figma の枠は面の内側に引かれる。
        // CSS の border は padding の外に足されるので、10 のままだと 2px 高くなる。
        // 9px にして枠のぶんを返すと、外形が設計どおり 38px / 40px になる
        assertThat(css).contains("padding: 9px 17px 9px 15px;");   // 受付の状態 → 9+18+9+2 = 38
        assertThat(css).contains("padding: 9px 12px 9px 14px;");   // 未提供     → 9+20+9+2 = 40

        assertThat(css).contains(".topbar__dot { width: 10px; height: 10px;");
        assertThat(css).contains(".topbar__pending .ic { width: 20px; height: 20px; flex: none; }");
        assertThat(css).contains(".topbar__pending-count { font-size: 17px; font-weight: 700;");
        assertThat(css).contains(".topbar__day-value { font-size: 17px; font-weight: 700;");
    }

    @Test
    @DisplayName("★ ロゴは画像のまま（設計の単色の丸は仮置き。店の印を消さない）")
    void brandKeepsTheShopLogo() throws Exception {
        assertThat(Files.readString(LAYOUT)).contains("class=\"topbar__logo\"");
        assertThat(css()).contains("object-fit: cover;");
    }

    @Test
    @DisplayName("★ th:classappend に BEM の __ を書かない（Thymeleaf が前処理記号として読む）")
    void stateModifierUsesIsPrefix() throws Exception {
        String layout = Files.readString(LAYOUT);

        // __ を書くとスタッフ側 31 画面がまとめてテンプレート解析エラーになる。
        // 修飾は is- 接頭辞で書く、というのがこの帯の約束
        assertThat(layout).contains("'is-on' : 'is-off'");

        // ★ 判定の前に Thymeleaf のコメント（<!--/* … */-->）を落とすこと。
        //   テンプレートには「'topbar__state--on' と書くと壊れる」という
        //   説明が書いてあり、素の doesNotContain だとその説明に一致して必ず落ちる。
        //   StockCategoryPickTest が .stock-chip で踏んだのと同じ罠です。
        String withoutComments = layout.replaceAll("(?s)<!--/\\*.*?\\*/-->", "");
        assertThat(withoutComments).doesNotContain("'topbar__state--");

        String css = css();
        assertThat(css).contains(".topbar__state.is-on  .topbar__dot { background: var(--ok); }");
        assertThat(css).contains(".topbar__state.is-off .topbar__dot { background: var(--danger); }");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 帯は描画され、受付の状態と営業日が出る")
    void topbarRenders() throws Exception {
        String html = mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("class=\"topbar\"");
        assertThat(html).contains("topbar__brand");
        // 受付中／停止中はどちらかが必ず出る（headerAccepting は 4 パッケージの
        // @ControllerAdvice が入れるので、スタッフ画面では null にならない）
        assertThat(html).satisfiesAnyOf(
                h -> assertThat(h).contains("注文を受付中"),
                h -> assertThat(h).contains("注文を停止中"));
        assertThat(html).contains("topbar__day-value");
        // ヘルプの行き先は店舗設定（ヘルプ文書は無い）
        assertThat(html).contains("topbar__help");
    }
}
