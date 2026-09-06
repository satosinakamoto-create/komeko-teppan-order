package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 売上・注文履歴・バックアップを設計に寄せる（2026-09-07）。
 *
 * <p><b>3 つの画面で期間の粒度を分けています。</b>
 * <ul>
 *   <li>ダッシュボード … 今日（いまどうなっているか）</li>
 *   <li>注文履歴       … 日を指定（あの日は何が出たか）</li>
 *   <li>売上           … 月（今月は。先月と比べて）</li>
 * </ul>
 * 同じ数字を違う切り口で見るので、集計そのものは
 * {@code SalesReportService} の 1 か所に置いたままにしています。
 * ここで独自に足し算を書くと、画面どうしで 1 円ずれたときに
 * どちらが正しいのか誰にも分からなくなります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("売上・注文履歴・バックアップ")
class SalesAndHistoryLayoutTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path SALES = Path.of("src/main/resources/templates/admin/sales.html");
    private static final Path ORDERS = Path.of("src/main/resources/templates/admin/orders.html");
    private static final Path BACKUPS = Path.of("src/main/resources/templates/admin/backups.html");

    @Test
    @DisplayName("★ 配分の列は 費目 → 金額 → 目標 → 実績 → 差（設計 14 売上）")
    void breakdownColumnOrder() throws Exception {
        String html = Files.readString(SALES);
        int at = html.indexOf("table--breakdown");
        String head = html.substring(at, html.indexOf("</thead>", at));
        assertThat(head.indexOf("費目")).isLessThan(head.indexOf("金額"));
        assertThat(head.indexOf("金額")).isLessThan(head.indexOf("目標"));
        assertThat(head.indexOf("目標")).isLessThan(head.indexOf("実績"));
        assertThat(head.indexOf("実績")).isLessThan(head.indexOf(">差<"));
    }

    @Test
    @DisplayName("★ 差は Java で計算する（テンプレートで計算しない）")
    void diffIsComputedInJava() throws Exception {
        // T(java.math.BigDecimal).valueOf(target()) と書いたら
        // SpEL が valueOf(Integer) を解決できず、画面ごと落ちた（実際に落とした）
        assertThat(Files.readString(SALES))
                .as("テンプレートで計算している").doesNotContain("T(java.math.BigDecimal)");

        AdminSalesController.BreakdownRow row = new AdminSalesController.BreakdownRow(
                "賃貸", 350000, new BigDecimal("27.2"), 10, "#000", true, false);
        assertThat(row.diff()).isEqualByComparingTo(new BigDecimal("17.2"));
        assertThat(row.over()).as("目標を上回っているのに over が false").isTrue();
        assertThat(row.bad()).as("費目は上回ったら赤").isTrue();

        // 記録が無い費目は差も出せない（0 と言い切ってはいけない）
        AdminSalesController.BreakdownRow none = new AdminSalesController.BreakdownRow(
                "人件費", null, null, 40, "#000", false, false);
        assertThat(none.diff()).isNull();
        assertThat(none.over()).isFalse();
    }

    @Test
    @DisplayName("★ L 人件費＋利益（残り）は、下回るほうが赤")
    void remainderIsRedWhenUnderTarget() throws Exception {
        // 設計 14 売上では 賃貸 ＋17.2 と L −20.5 が「どちらも赤」。
        // 符号だけで塗り分けると、手取りの不足が良いことに見えてしまう
        AdminSalesController.BreakdownRow labor = new AdminSalesController.BreakdownRow(
                "L 人件費＋利益", 249800, new BigDecimal("19.5"), 40, "#000", true, true);
        assertThat(labor.diff()).isEqualByComparingTo(new BigDecimal("-20.5"));
        assertThat(labor.over()).isFalse();
        assertThat(labor.bad()).as("残りが目標を下回っているのに赤くならない").isTrue();
    }

    @Test
    @DisplayName("★ 売れ筋にカテゴリの列がある（設計 14 売上）")
    void rankingHasCategory() throws Exception {
        assertThat(Files.readString(SALES)).contains("${r.category()}");
        assertThat(Files.readString(ORDERS)).contains("${r.category()}");
    }

    @Test
    @DisplayName("★ 消した商品の売上を落とさない（left join にする）")
    void deletedItemsStillCount() throws Exception {
        // 内部結合にすると、消した商品の売上が集計から丸ごと消え、
        // 総売上と足し合わなくなる
        String repo = Files.readString(
                Path.of("src/main/java/jp/komeko/order/repository/OrderRepository.java"));
        assertThat(repo.split("left join MenuItem m on m.id = l.menuItemId", -1).length - 1)
                .as("ランキングの結合が left join になっていない").isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 注文履歴に、その日の数字と売れた商品が出る")
    void historyShowsTheDaysNumbers() throws Exception {
        String html = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("その日の売上が無い").contains("この日の売上");
        assertThat(html).as("1 注文あたりが無い").contains("1 注文あたり");
        // 日を指定する口は前からある。粒度の分担がここで崩れていないこと
        assertThat(html).contains("この日を表示");
    }

    @Test
    @DisplayName("★ 構成比の棒は CSS だけで描く（店内 Wi-Fi にネットが無くても崩れない）")
    void shareBarNeedsNoScript() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/css/app.css"));
        assertThat(css).contains(".sharebar__fill");
        assertThat(Files.readString(ORDERS)).contains("sharebar__fill");
        // 画像も script も使っていない
        assertThat(Files.readString(ORDERS)).doesNotContain("<script");
    }

    @Test
    @DisplayName("★ バックアップに「復元する」のワンタップは付けない")
    void noOneTapRestore() throws Exception {
        // 復元は本番のデータを丸ごと置き換える操作。手順書では隔離フォルダへ展開し、
        // 別ポートで開いて中身を確かめてから入れ替える。
        // ワンタップの口を作ると、その確かめる工程ごと飛ばせてしまう
        String html = Files.readString(BACKUPS).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html).as("ワンタップの復元が付いている")
                .doesNotContain("/admin/backups/restore");
    }

    @Test
    @DisplayName("「今すぐ取る」は 1 画面に 1 つだけ")
    void oneRunButtonOnly() throws Exception {
        String html = Files.readString(BACKUPS).replaceAll("(?s)<!--.*?-->", "");
        assertThat(html.split("/admin/backups/run", -1).length - 1)
                .as("同じボタンが 2 つある").isEqualTo(1);
    }
}
