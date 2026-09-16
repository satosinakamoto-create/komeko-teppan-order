package jp.komeko.order.inventory.web;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.InventoryTestFixture;
import org.junit.jupiter.api.BeforeEach;
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
 * 原価表の「開く道」は 1 つだけ（2026-09-16、店主の指摘）。
 *
 * <p><b>店主の指摘</b>——「商品名が緑で、編集ボタンと両方押しても同じ画面になる」。
 * そのとおりで、商品名のリンクと操作列のボタンが<b>まったく同じ URL</b>を指していました。
 *
 * <p><b>同じ場所へ行く道を 2 つ持たない</b>のは、このプロジェクトで何度か採ってきた判断です
 * （QR の印刷の入口を卓に一本化したのと同じ）。押す前にどちらを押すか考えさせるだけで、
 * 得るものがありません。
 *
 * <p>直した形:
 * <ul>
 *   <li>商品名は<b>ただの文字</b>（黒）。押せそうに見せない</li>
 *   <li>操作列のボタンを {@code .recbtn}（白地・緑枠・緑文字）に。
 *       食材・在庫の棚卸し「記録する」と同じ見た目で、<b>表の中の入口はこれ</b>と分かる</li>
 * </ul>
 *
 * <p>ファイルを読むだけのテストなので Spring の描画は 1 件だけにしています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("原価表の開く道は 1 つ")
class RecipeListActionTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/recipes.html");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryTestFixture fixture;

    private MenuItem dish;

    @BeforeEach
    void setUp() {
        dish = fixture.createMenuItem("原価表テスト用お好み焼き-" + System.nanoTime(), 1180);
    }

    private String template() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 商品名はリンクにしない（押しても同じ画面へ行くだけ）")
    void theDishNameIsNotALink() throws Exception {
        String html = template();

        int at = html.indexOf("c.menuItem().name");
        assertThat(at).as("商品名の列が無い").isGreaterThan(0);

        // 商品名の前後 200 文字に <a ...> が無いこと
        String around = html.substring(Math.max(0, at - 200), at);
        assertThat(around)
                .as("商品名がまだリンクになっている（編集ボタンと同じ行き先）")
                .doesNotContain("<a ");
    }

    /**
     * ★ 白地・緑枠・緑文字（店主の指定）。
     *
     * <p>棚卸しの「記録する」と同じ {@code .recbtn} を使い回します。
     * 同じ「表の行からその先へ入る」用途なので、部品を増やしません。
     */
    @Test
    @DisplayName("★ 操作のボタンは 記録する と同じ見た目（.recbtn）")
    void theActionButtonLooksLikeTheRecordButton() throws Exception {
        String html = template();

        assertThat(html).as(".recbtn を使っていない").contains("class=\"recbtn\"");
        assertThat(html).as("元の btn--ghost が残っている")
                .doesNotContain("btn btn--sm btn--ghost");
    }

    /**
     * .recbtn が白地・緑枠・緑文字であることを CSS 側でも確かめます。
     * テンプレートだけ見ても、クラスの中身が変われば見た目は変わるためです。
     */
    @Test
    @DisplayName("★ .recbtn は白地・緑枠・緑文字のまま")
    void theRecordButtonKeepsItsLook() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"))
                .replace("\r\n", "\n");

        int at = css.indexOf(".recbtn {");
        assertThat(at).as(".recbtn が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("枠が緑でない").contains("border: 1px solid var(--accent)");
        assertThat(rule).as("文字が緑でない").contains("color: var(--accent)");
        assertThat(rule).as("地が白でない").contains("background: var(--bg-elevated)");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 一覧が描けて、編集へ行く道が 1 本だけある")
    void theListStillOpensAndHasExactlyOneWayIn() throws Exception {
        String html = mockMvc.perform(get("/inventory/recipes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String target = "/inventory/recipes/" + dish.getId();
        int count = html.split(java.util.regex.Pattern.quote(target + "\""), -1).length - 1;
        assertThat(count)
                .as("この商品の編集画面へ行くリンクが %d 本ある（1 本のはず）", count)
                .isEqualTo(1);
    }
}
