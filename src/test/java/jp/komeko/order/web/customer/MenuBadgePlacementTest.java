package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「おすすめ」などの札を、品名の<b>上</b>に置く（2026-09-06）。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 *
 * <p><b>なぜ上なのか</b><br>
 * 写真カードは札を写真に重ねて上に出しています。
 * サムネイルと行だけ品名の下に出ていたので、
 * 同じ札が画面の中で 2 通りの位置に現れていました。
 *
 * <p>下に置くと、品名と価格を読んだあとで札が目に入ります。
 * 「おすすめだから見た」ではなく「見たあとにおすすめと知る」順になり、
 * 札の役目が半分になります。
 */
@DisplayName("メニューの札の位置")
class MenuBadgePlacementTest {

    private static final Path MENU_HTML =
            Path.of("src/main/resources/templates/customer/menu.html");
    private static final Path APP_CSS =
            Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("★ サムネイルは 写真 → 札 → 品名 → 価格 の順")
    void thumbnailPutsBadgesAboveTheName() throws Exception {
        String html = Files.readString(MENU_HTML);
        int at = html.indexOf("class=\"thumb__img\"");
        assertThat(at).as("サムネイルが見つからない").isGreaterThan(-1);
        String block = html.substring(at, html.indexOf("</a>", at));

        assertThat(block.indexOf("allergens"))
                .as("札が品名より後ろにある")
                .isLessThan(block.indexOf("thumb__name"));
        assertThat(block.indexOf("thumb__name"))
                .as("品名が価格より後ろにある")
                .isLessThan(block.indexOf("thumb__price"));
    }

    @Test
    @DisplayName("★ 行（ドリンク・写真の無い品）も、札が品名より先")
    void rowPutsBadgesAboveTheName() throws Exception {
        String html = Files.readString(MENU_HTML);

        // 行は 2 か所ある（ドリンクの一覧と、お食事で写真の無い品）。
        // 片方だけ直すと、同じ形の行なのに札の位置が違うことになる
        int from = 0, 直っている = 0, 総数 = 0;
        while (true) {
            int at = html.indexOf("class=\"drink-row__body\"", from);
            if (at < 0) break;
            総数++;
            String block = html.substring(at, html.indexOf("</span>\n              <span class=\"drink-row__price", at) + 1);
            if (block.indexOf("allergens") >= 0
                    && block.indexOf("allergens") < block.indexOf("drink-row__name")) {
                直っている++;
            }
            from = at + 1;
        }
        assertThat(総数).as("行が見つからない").isGreaterThanOrEqualTo(2);
        assertThat(直っている).as("札が品名より後ろの行が残っている").isEqualTo(総数);
    }

    @Test
    @DisplayName("★ 札が無い品で、置き場が場所を取らない")
    void emptyBadgeBoxTakesNoSpace() throws Exception {
        String css = Files.readString(APP_CSS);

        // 札を持つ品のほうが少ないので、空の枠が場所を取ると影響は全体に出る。
        // ★ :empty ではなく :not(:has(*))。
        //   :empty は改行や字下げの空白も子として数えるので、
        //   Thymeleaf が残す空白に一致せず効かない（実際それで効かなかった）
        assertThat(css).as("空の札置き場が場所を取る")
                .contains(".menu-item__badges:not(:has(*)) { display: none; }");
        assertThat(css).as(":empty では Thymeleaf の空白に一致しない")
                .doesNotContain(".menu-item__badges:empty");
    }

    @Test
    @DisplayName("札の上余白は親の間隔にまかせる（写真との間だけ広くならない）")
    void badgesHaveNoOwnTopMargin() throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(".thumb > .menu-item__badges,");
        assertThat(at).as("上余白の打ち消しが無い").isGreaterThan(-1);
        assertThat(css.substring(at, css.indexOf('}', at))).contains("margin-top: 0");
    }
}
