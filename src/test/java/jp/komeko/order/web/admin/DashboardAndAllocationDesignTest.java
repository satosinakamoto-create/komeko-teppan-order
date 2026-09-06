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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ダッシュボード（設計 01 15:319）と売上の配分の帯グラフ（設計 14 290:2012）。
 * 2026-09-07。「URL 指定で頼んだのに出来ていないものがある」という指摘を受けて、
 * 設計と実装を突き合わせ直したときの差分。
 *
 * <p><b>ダッシュボードで直したこと</b>
 * <ul>
 *   <li>比較は前日比 → <b>先週の同じ曜日</b>。居酒屋の売上は曜日でまるごと変わるので、
 *       前日比だと毎週月曜に「大きく落ちた」と出る。</li>
 *   <li>推移は直近 7 日 → <b>今日の時間帯別</b>。朝いちばんに見る画面が受け持つのは今日。</li>
 *   <li>「ただいまの注文受付」の大きなパネルを撤去（上の帯のピルと二重だった）。</li>
 * </ul>
 *
 * <p><b>売上で足したもの</b>：目標と実績の帯グラフ、ことわり書き、縦軸の目盛り。
 * 表の数字は前からあったが、「どこが太ったか」は帯にしないと一目で読めない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ダッシュボードと売上の配分（設計突き合わせ 2026-09-07）")
class DashboardAndAllocationDesignTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path HOME = Path.of("src/main/resources/templates/admin/home.html");
    private static final Path SALES = Path.of("src/main/resources/templates/admin/sales.html");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    // ========================================================================
    //  時間帯別の折れ線（SalesView.hourlyChart）
    // ========================================================================

    @Test
    @DisplayName("★ 深夜またぎでも 17時 → 23時 → 0時 → 2時 の順に並ぶ")
    void hourlyChartCrossesMidnightInBusinessOrder() {
        // 時刻の小さい順に並べると、閉店間際（0〜2時）がグラフの左端に来てしまう
        SalesView.Chart chart = SalesView.hourlyChart(
                Map.of(18, 3000L, 1, 500L), 17, 2, 5);

        List<String> labels = chart.points().stream().map(SalesView.ChartPoint::label).toList();
        assertThat(labels).containsExactly("17時", "18時", "19時", "20時", "21時",
                "22時", "23時", "0時", "1時", "2時");
    }

    @Test
    @DisplayName("★ ピークは最大の点 1 つだけ（金額を添える目印）")
    void peakIsOnlyTheMaximumPoint() {
        // 1 時間刻みは点が 10 個。全部に金額を書くと文字が重なるので、
        // 設計（15:319）どおりピークにだけ添える
        SalesView.Chart chart = SalesView.hourlyChart(
                Map.of(18, 3000L, 20, 7100L, 21, 7100L), 17, 2, 5);

        List<SalesView.ChartPoint> peaks = chart.points().stream()
                .filter(SalesView.ChartPoint::peak).toList();
        assertThat(peaks).hasSize(1);
        assertThat(peaks.get(0).label()).isEqualTo("20時");   // 同額なら早いほう
        assertThat(peaks.get(0).value()).isEqualTo(7100L);
    }

    @Test
    @DisplayName("売上ゼロの日はピーク無し（¥0 に金額を添えない）")
    void noPeakWhenNoSales() {
        SalesView.Chart chart = SalesView.hourlyChart(Map.of(), 17, 2, 5);
        assertThat(chart.points()).noneMatch(SalesView.ChartPoint::peak);
    }

    // ========================================================================
    //  ダッシュボードの画面
    // ========================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ ダッシュボードは今日の時間帯別・先週の同じ曜日と比べる")
    void dashboardShowsTodayByHour() throws Exception {
        String html = mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("時間帯別の売上");
        assertThat(html).contains("先週の同じ曜日と比べて");
        // 受付状態の大きなパネルは置かない（上の帯のピルと二重になる）
        assertThat(html).doesNotContain("ただいまの注文受付");
        // 値には単位を添える（「3 卓」。数字だけだと 34 に見間違う）
        assertThat(html).contains("statcard__unit");
    }

    @Test
    @DisplayName("★ 注文数の差は % ではなく件数で出す")
    void ordersDeltaIsACount() throws Exception {
        // 12 件 → 14 件を「＋16.7%」と書かれても現場ではピンとこない。
        // 設計（15:319）も「＋2 件」
        String html = Files.readString(HOME);
        assertThat(html).contains("ordersDiff");
        assertThat(html).contains("' 件'");
    }

    // ========================================================================
    //  売上の配分（帯グラフ）
    // ========================================================================

    @Test
    @DisplayName("★ 目標と実績の帯・ことわり書き・縦軸の目盛りがテンプレートにある")
    void salesHasAllocationBarsAndAxis() throws Exception {
        String html = Files.readString(SALES);
        assertThat(html).contains("alloc.targetBar()");
        assertThat(html).contains("alloc.actualBar()");
        assertThat(html).contains("alloc.rentNote()");
        // 縦軸の目盛り（¥120万 など）。設計にはあるのに描いていなかった
        assertThat(html).contains("linechart__ylabel");
        // 金額は点のそばへ移した。軸の下に金額の <b> は残さない
        assertThat(html).contains("linechart__plabel");
        assertThat(Files.readString(HOME)).contains("linechart__ylabel");
    }

    @Test
    @DisplayName("★ 帯は家賃が入っているときだけ（残りが計算できないと嘘の帯になる）")
    void barsRequireTheRent() {
        AdminSalesController.Allocation without = new AdminSalesController.Allocation(
                List.of(), null, null, null, null);
        assertThat(without.hasBars()).isFalse();

        AdminSalesController.Allocation with = new AdminSalesController.Allocation(
                List.of(),
                List.of(new AdminSalesController.AllocSeg("allocbar__seg--f", new BigDecimal("30"), "30%")),
                List.of(), null, null);
        assertThat(with.hasBars()).isTrue();
    }

    @Test
    @DisplayName("★ 幅 9% 未満の区画は % を書かない（設計では 8.1% が無記入）")
    void narrowSegmentsHideTheirLabel() {
        assertThat(new AdminSalesController.AllocSeg("x", new BigDecimal("8.1"), "8%").showLabel())
                .as("狭い区画に書くと溢れる").isFalse();
        assertThat(new AdminSalesController.AllocSeg("x", new BigDecimal("10"), "10%").showLabel())
                .isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("家賃が未記録の月は、帯を出さず案内を出す")
    void withoutRentTheTableStillRenders() throws Exception {
        // テスト DB は家賃 0 で始まる。このときに帯を黙って消すだけだと
        // 「何をすれば出るのか」が分からない
        String html = mockMvc.perform(get("/admin/sales"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("table--breakdown");
        assertThat(html).contains("帯グラフを出すには");
    }

    // ========================================================================
    //  画面の寸法（CSS の要）
    // ========================================================================

    @Test
    @DisplayName("帯・ことわり・記録ページの寸法が設計値のまま")
    void designMetricsArePinned() throws Exception {
        // Windows の改行（\r\n）のまま比べると、複数行の contains が
        // 黙って外れる（topRule の教訓と同じ）。先にそろえる
        String css = Files.readString(CSS).replace("\r\n", "\n");

        // 帯（290:2020）：高さ 24・角丸 6・% は 11px
        assertThat(css).contains("height: 24px;\n  border-radius: 6px;");
        assertThat(css).contains(".allocbar__seg span { font-size: 11px; font-weight: 700; }");
        // ことわり（290:2045）：危険薄の地
        assertThat(css).contains("background: var(--danger-soft);");

        // 記録ページ（現05 443:2940）：題 32・カード見出し帯 14px 20px・カード間 16
        assertThat(css).contains(".recpage-head h1 { margin: 0; font-size: 32px;");
        assertThat(css).contains("padding: 14px 20px;");
        // .theme-desk .grid { gap:24px } に負けないよう 2 段で書いてある。
        // 単独の .recgrid に戻すと specificity の同点負けで 24px に戻る（実際に負けた）
        assertThat(css).contains(".theme-desk .recgrid { gap: 16px; }");
    }
}
