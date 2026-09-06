package jp.komeko.order.web.admin;

import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.inventory.service.PurchaseSummary;
import jp.komeko.order.service.SalesReportService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import jp.komeko.order.service.dto.DailySales;
import jp.komeko.order.service.dto.DaySummary;
import jp.komeko.order.service.dto.ItemSales;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 売上の集計画面（管理者のみ）。
 *
 * <p>集計そのものは {@link SalesReportService} が行います。
 * このコントローラの仕事は「どの日を見るか決める」「表示用に整える」の 2 つだけです。
 *
 * <p><b>数字が 2 種類あることに注意</b><br>
 * イートインでは<b>お会計の単位は伝票（{@link TableSession}）</b>です。
 * ところが {@link SalesReportService} は「受渡済の注文の合計」を売上とみなす作りなので、
 * テーブルチャージと深夜料金が入りません。
 * どちらか一方を消すのではなく、
 * <b>「伝票ベース（実際にいただいた金額）」と「注文ベース（商品の売れ行き）」を並べて出し、
 * それぞれ何の数字かを画面に明記する</b>方針にしています。
 * 数字が 2 つあること自体より、どちらが何なのか分からないことのほうが現場は混乱します。
 *
 * <p><b>グラフを外部ライブラリなしで描く</b><br>
 * 店内の PC はインターネットに繋がっていないこともあるため、
 * CDN からグラフライブラリを読み込む作りにはしません。
 * app.css の {@code .bars} 一式（棒グラフ）を使い、棒の高さだけを
 * {@code th:style="'height:○%'"} で指定します。
 * その % の計算は、0 除算やまるめの都合をテンプレートに持ち込まないよう
 * <b>すべて Java 側で済ませて</b>から渡します。
 */
@Controller
@RequestMapping("/admin/sales")
public class AdminSalesController {

    /** 推移グラフに出す日数。 */
    private static final int RECENT_DAYS = 7;

    /**
     * 棒の最大の高さ（%）。
     * {@code .bars__item} の中には棒とラベルが縦に並ぶので、
     * 棒を 100% にするとラベルの分だけ枠からはみ出します。少し余裕を残します。
     */
    private static final double BAR_MAX_PERCENT = 85.0;

    /** 見出し用「2026年8月16日(日)」。曜日を日本語で出すため Locale.JAPAN を指定する。 */
    private static final DateTimeFormatter TITLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN);

    /** グラフの目盛り用「8/16」。 */
    private static final DateTimeFormatter BAR_FORMAT =
            DateTimeFormatter.ofPattern("M/d", Locale.JAPAN);

    private final SalesReportService salesReportService;
    private final ShopSettingService shopSettingService;
    private final TableService tableService;
    /**
     * 仕入れの集計。
     *
     * <p>在庫モジュール（{@code app.inventory.enabled}）を切ると Bean が存在しないので
     * {@code ObjectProvider} で受けます。無いときは配分を「記録なし」で描きます。
     */
    private final org.springframework.beans.factory.ObjectProvider<PurchaseService> purchaseServiceProvider;

    public AdminSalesController(SalesReportService salesReportService,
                                ShopSettingService shopSettingService,
                                TableService tableService,
                                org.springframework.beans.factory.ObjectProvider<PurchaseService> purchaseServiceProvider) {
        this.salesReportService = salesReportService;
        this.shopSettingService = shopSettingService;
        this.tableService = tableService;
        this.purchaseServiceProvider = purchaseServiceProvider;
    }

    /**
     * 売上画面。
     *
     * <p>{@code @DateTimeFormat(iso = DATE)} を付けると、
     * {@code ?date=2026-08-16} のような文字列を {@link LocalDate} に変換してくれます。
     * これが無いと環境のロケール次第で解釈が変わり、ある PC では動くのに
     * 別の PC では 400 エラー、という再現しづらい不具合になります。
     *
     * <p>{@code required = false} なので未指定なら null。
     * そのときは「いまの営業日」を見ます（深夜営業を考慮した営業日で、暦の今日とは限りません）。
     */
    @GetMapping
    public String sales(@RequestParam(required = false) String month,
                        @RequestParam(required = false, defaultValue = "6") int span,
                        Model model) {

        YearMonth target = parseMonth(month);
        salesViewModel(model, target, span);
        return "admin/sales";
    }

    /** 折れ線に出せる月数。ここに無い値が来たら 6 に倒す。 */
    private static final List<Integer> SPANS = List.of(1, 3, 6, 12);

    /**
     * 月の文字列（{@code 2026-08}）を読む。
     *
     * <p>読めない値が来たら今の営業月に倒します。
     * URL を手で打ち替えられても 400 にせず、必ず何かが表示される側に寄せています。
     */
    private YearMonth parseMonth(String month) {
        if (month != null && !month.isBlank()) {
            try {
                return YearMonth.parse(month);
            } catch (RuntimeException ignored) {
                // 読めない値は無視して、いまの月に倒す
            }
        }
        return YearMonth.from(shopSettingService.currentBusinessDate());
    }

    /**
     * 売上画面（月単位）に渡す値をまとめて作る。
     *
     * <p>ダッシュボードからも同じ形で呼べるように、モデルへの詰め込みだけを切り出してあります。
     */
    private void salesViewModel(Model model, YearMonth target, int span) {
        int months = SPANS.contains(span) ? span : 6;

        SalesReportService.MonthlySales now = salesReportService.monthlySummary(target);
        SalesReportService.MonthlySales prev = salesReportService.monthlySummary(target.minusMonths(1));
        List<SalesReportService.MonthlySales> series = salesReportService.monthlySeries(target, months);
        List<ItemSales> ranking = salesReportService.monthlyRanking(target);

        model.addAttribute("sales", now);
        model.addAttribute("prevSales", prev);
        model.addAttribute("salesDelta", SalesView.deltaPercent(now.sales(), prev.sales()));
        model.addAttribute("ordersDelta", SalesView.deltaPercent(now.orders(), prev.orders()));
        model.addAttribute("averageDelta",
                SalesView.deltaPercent(now.averagePerBill(), prev.averagePerBill()));

        model.addAttribute("chart", SalesView.chart(
                series.stream().map(s -> s.month().getMonthValue() + "月").toList(),
                series.stream().map(SalesReportService.MonthlySales::sales).toList()));
        model.addAttribute("spans", SPANS);
        model.addAttribute("span", months);

        Allocation alloc = allocationOf(target, now.sales(),
                shopSettingService.currentReadOnly().getMonthlyRent());
        model.addAttribute("breakdown", alloc.rows());
        model.addAttribute("alloc", alloc);
        // レジ締め用の内訳。名前を settlement にしているのは、上の breakdown
        // （原価・人件費などの費用の内訳）と混ざらないようにするため。
        model.addAttribute("settlement",
                salesReportService.settlementBreakdown(target.atDay(1), target.atEndOfMonth()));
        model.addAttribute("ranking", SalesView.ranking(ranking, now.sales()));

        // 月は「表示用」と「URL 用」を分けて渡す。
        // YearMonth をそのままリンクに埋めると環境のロケールで形が変わり、
        // そのリンクを踏んだ瞬間に読めなくなることがある。
        model.addAttribute("monthIso", target.toString());
        model.addAttribute("monthLabel", target.toString());
        model.addAttribute("prevMonthIso", target.minusMonths(1).toString());
        model.addAttribute("nextMonthIso", target.plusMonths(1).toString());
    }

    /**
     * 前月比（%）。前月が 0 なら null。
     *
     * <p>0 からの伸びを「＋100%」と書くと、1 円でも売れた月が
     * 満点のように見えてしまいます。比べられないときは比べない。
     */
    private static java.math.BigDecimal deltaPercent(long now, long prev) {
        if (prev <= 0) {
            return null;
        }
        return java.math.BigDecimal.valueOf(now - prev)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(prev), 1, java.math.RoundingMode.HALF_UP);
    }

    // ========================================================================
    //  売上の配分（何にいくら出ていったか）
    // ========================================================================

    /**
     * 配分の 1 行。
     *
     * @param label   費目の名前
     * @param amount  金額。記録が無い費目は null
     * @param percent 売上に対する割合。金額が null なら null
     * @param target  目安の割合（%）
     * @param color   帯の色
     */
    public record BreakdownRow(String label, Integer amount, java.math.BigDecimal percent,
                               int target, String color, boolean recorded, boolean lowerIsBad) {

        /**
         * 実績 − 目標（％ポイント）。記録が無ければ null。
         *
         * <p>設計 14 売上（17:1038）の「差」の列です。
         * 目標と実績を並べただけだと、引き算は店主の仕事になります。
         *
         * <p><b>テンプレートで計算しないこと。</b>
         * {@code T(java.math.BigDecimal).valueOf(target())} と書いたら、
         * SpEL が {@code valueOf(Integer)} を解決できずに画面ごと落ちました。
         * 計算は Java に置けば型で守られます（CLAUDE.md の
         * 「業務ロジックはコントローラに書かず Service に置く」と同じ考え）。
         */
        public java.math.BigDecimal diff() {
            return percent == null ? null
                    : percent.subtract(java.math.BigDecimal.valueOf(target));
        }

        /** 目標を上回っているか（費目なので、上回る＝使いすぎ）。 */
        public boolean over() {
            java.math.BigDecimal d = diff();
            return d != null && d.signum() > 0;
        }

        /**
         * 悪い方向にずれているか（赤で出すか）。
         *
         * <p>費目は「上回る＝使いすぎ」で悪い。
         * ただし <b>L 人件費＋利益（残り）は逆</b>で、下回るほうが悪い。
         * 設計 14 売上（17:1038）でも、賃貸の ＋17.2 と L の −20.5 が
         * <b>どちらも赤</b>です。符号で塗り分けると L の不足が良いことに見えます。
         */
        public boolean bad() {
            java.math.BigDecimal d = diff();
            if (d == null) {
                return false;
            }
            return lowerIsBad ? d.signum() < 0 : d.signum() > 0;
        }
    }

    /**
     * 帯グラフの 1 区画。
     *
     * @param cls    色を決める CSS クラス（allocbar__seg--f など）
     * @param weight 幅の重み（％の値そのまま。flex-grow に入れる）
     * @param text   区画の中に書く割合（丸めた「34%」）
     */
    public record AllocSeg(String cls, java.math.BigDecimal weight, String text) {

        /** 区画の中に % を書くか。設計では 8.1% の区画は無記入（幅が足りず溢れる）。 */
        public boolean showLabel() {
            return weight != null && weight.doubleValue() >= 9;
        }
    }

    /**
     * 売上の配分ひとそろい（明細の行＋帯グラフ＋ことわり書き）。
     *
     * <p>帯（targetBar / actualBar）は、売上と家賃がそろっているときだけ作ります。
     * 家賃が未記録のまま「残り」を出すと家賃のぶん膨らんだ嘘になるためで、
     * そのときは明細の表だけを出します（{@code hasBars()} で分岐）。
     */
    public record Allocation(List<BreakdownRow> rows,
                             List<AllocSeg> targetBar, List<AllocSeg> actualBar,
                             String rentNote, String remainNote) {

        public boolean hasBars() {
            return targetBar != null && !targetBar.isEmpty();
        }
    }

    /**
     * 目安の割合（%）。飲食店で一般に言われる FL 比率にそろえてあります。
     *
     * <p><b>FL の 60% は「F と L を足して 60」という意味です（2026-09-05 に修正）。</b>
     * もとは {@code F 食材・飲料} の行に 60 を、
     * {@code L 人件費＋利益} の行に 10 を当てていました。
     * つまり <b>L が 60 の外へ出たまま、F 単独の目安が 60 のまま</b>でした。
     *
     * <p>実績の F はふつう 25〜35% に収まるので、
     * 原価率が正常な月でも「実績 30% / 目安 60%」と並び、
     * <b>「目安の半分しか使っていない＝倍まで仕入れてよい」と読めてしまいます。</b>
     * 行ごとの目安を足すと 100 になっていたので、表の中では破綻が見えませんでした。
     *
     * <p>いまは F 30 ／ L 30（合わせて FL 60）／ 光熱 10 ／ 雑費 10 ／ 賃貸 10 で、
     * 合計 90。残りの約 10% が営業利益にあたります。
     */
    private static final int TARGET_FOOD = 30;
    private static final int TARGET_LABOR = 30;
    private static final int TARGET_UTILITIES = 10;
    private static final int TARGET_SUNDRY = 10;
    private static final int TARGET_RENT = 10;
    /** 目安の残り約 10% ＝ 営業利益。帯グラフと「L 人件費＋利益」の目標に使う。 */
    private static final int TARGET_PROFIT = 10;

    /**
     * 売上の配分。
     *
     * <p><b>目安（F 30 ／ L 30 ＝ FL 60 ／ 光熱 10 ／ 雑費 10 ／ 賃貸 10）と、実績を並べて出します。</b>
     * 目安は業種の一般論で、実績はこのアプリに記録された仕入れです。
     *
     * <p><b>賃貸は店舗設定の「家賃（月額）」から入れます（2026-09-05）。</b>
     * 家賃は仕入れではなく毎月同じ額が出ていく固定費なので、
     * {@code PurchaseCategory}（食材／飲料・酒／消耗品／光熱費／その他）には入れず、
     * {@link ShopSetting#getMonthlyRent()} として 1 つ持っています。
     * 仕入れに混ぜると帳簿では雑費になり、税理士に渡す仕訳が狂います。
     *
     * <p><b>家賃が 0 のときは「記録していない」のままにします。</b>
     * 0 円と出すと「家賃がかかっていない」という嘘になるためで、
     * 金額が入っているときだけ実績に載せます。
     *
     * <p><b>人件費は、いまも記録する場所がありません。</b>
     * <b>数字を決め打ちで書き込むことはしません。</b>
     * 画面には「記録していない」と出して、
     * 足りない費目があることが分かる状態にしておきます。
     *
     * @param monthlyRent 店舗設定の月額家賃（税込・円）。0 なら未記録として扱う
     */
    private Allocation allocationOf(YearMonth month, long sales, int monthlyRent) {
        PurchaseService purchaseService = purchaseServiceProvider.getIfAvailable();
        if (purchaseService == null) {
            // 在庫モジュールを切っているときは仕入れの記録そのものが無い。
            // 目安だけを出して、実績は「記録していない」にする。
            // ただし家賃は在庫モジュールと無関係なので、設定があればここでも出す
            List<BreakdownRow> none = new ArrayList<>();
            none.add(new BreakdownRow("F 食材・飲料", null, null, TARGET_FOOD, "var(--border)", false, false));
            none.add(new BreakdownRow("光熱費", null, null, TARGET_UTILITIES, "var(--border)", false, false));
            none.add(new BreakdownRow("雑費（消耗品・その他）", null, null, TARGET_SUNDRY, "var(--border)", false, false));
            none.add(rentRow(monthlyRent, sales));
            none.add(new BreakdownRow("L 人件費", null, null, TARGET_LABOR, "var(--border)", false, false));
            return new Allocation(none, null, null, null, null);
        }
        PurchaseSummary p = purchaseService.summarize(month);

        int food = 0;
        int drink = 0;
        int supplies = 0;
        int utilities = 0;
        int other = 0;
        for (PurchaseSummary.CategoryRow row : p.categories()) {
            switch (row.category()) {
                case FOOD -> food = row.amountIncludeTax();
                case DRINK -> drink = row.amountIncludeTax();
                case SUPPLIES -> supplies = row.amountIncludeTax();
                case UTILITIES -> utilities = row.amountIncludeTax();
                case OTHER -> other = row.amountIncludeTax();
            }
        }

        // しるしの色は設計 14 売上（17:1038）の帯と同じにする。
        // 帯と表で同じ費目が違う色だと、対応が目で追えない
        BreakdownRow foodRow = row("F 食材・飲料", food + drink, sales, TARGET_FOOD, "var(--green-700)");
        BreakdownRow utilRow = row("光熱費", utilities, sales, TARGET_UTILITIES, "var(--green-100)");
        BreakdownRow miscRow = row("雑費（消耗品・その他）", supplies + other, sales, TARGET_SUNDRY, "var(--surface-2)");
        BreakdownRow rentR = rentRow(monthlyRent, sales);

        List<BreakdownRow> rows = new ArrayList<>();
        rows.add(foodRow);
        rows.add(utilRow);
        rows.add(miscRow);
        rows.add(rentR);

        // ★ L は「売上から、記録のある費目を全部引いた残り」＝人件費＋利益。
        //   人件費そのものは記録する場所がまだ無いが、残りなら嘘なく計算できる
        //   （設計 14 売上の「L 人件費＋利益」の行）。
        //   ただし家賃が未記録だと、残りが家賃のぶん膨らんで嘘になる。
        //   その場合は従来どおり「記録していない」の行にして、帯も出さない。
        if (sales <= 0 || monthlyRent <= 0) {
            rows.add(new BreakdownRow("L 人件費", null, null, TARGET_LABOR, "var(--border)", false, false));
            return new Allocation(rows, null, null, null, null);
        }

        long remainder = sales - (food + drink + supplies + utilities + other) - monthlyRent;
        BreakdownRow laborRow = new BreakdownRow("L 人件費＋利益", (int) remainder,
                SalesView.percent(remainder, sales), TARGET_LABOR + TARGET_PROFIT,
                "var(--green-600)", true, true);
        rows.add(laborRow);

        // ── 帯グラフ ──
        // 目標の帯は固定（F30／L30／光熱10／雑費10／賃貸10／利益10 ＝ 100）。
        // 実績の帯は 5 区画（L と利益は「残り」として 1 区画にまとまる）で、
        // % がそのまま幅の重みになるので、合計は必ず 100 になる
        List<AllocSeg> targetBar = List.of(
                new AllocSeg("allocbar__seg--f", java.math.BigDecimal.valueOf(TARGET_FOOD), TARGET_FOOD + "%"),
                new AllocSeg("allocbar__seg--l", java.math.BigDecimal.valueOf(TARGET_LABOR), TARGET_LABOR + "%"),
                new AllocSeg("allocbar__seg--util", java.math.BigDecimal.valueOf(TARGET_UTILITIES), TARGET_UTILITIES + "%"),
                new AllocSeg("allocbar__seg--misc", java.math.BigDecimal.valueOf(TARGET_SUNDRY), TARGET_SUNDRY + "%"),
                new AllocSeg("allocbar__seg--rent", java.math.BigDecimal.valueOf(TARGET_RENT), TARGET_RENT + "%"),
                new AllocSeg("allocbar__seg--profit", java.math.BigDecimal.valueOf(TARGET_PROFIT), TARGET_PROFIT + "%"));

        // 賃貸の区画は、目標を超えたら赤にする（設計の 27.2% が赤いのはこれ）。
        // 色だけの違いなので、超えていない月は薄いグレーのまま
        List<AllocSeg> actualBar = List.of(
                seg("allocbar__seg--f", foodRow.percent()),
                seg("allocbar__seg--l", laborRow.percent()),
                seg("allocbar__seg--util", utilRow.percent()),
                seg("allocbar__seg--misc", miscRow.percent()),
                seg(rentR.over() ? "allocbar__seg--rent-over" : "allocbar__seg--rent", rentR.percent()));

        // ── ことわり書き ──
        // 「目標の 10% に収めるには月商いくら要るか」は家賃からの逆算。
        // 割合だけ見せられても、店主が決められるのは月商の目標のほう
        String rentNote = null;
        if (rentR.over()) {
            long needed = (long) monthlyRent * 100 / TARGET_RENT;
            rentNote = "賃貸 ¥%,d が売上の %s%%。目標の %d%% に収めるには月商 ¥%,d が要ります。"
                    .formatted(monthlyRent, rentR.percent(), TARGET_RENT, needed);
        }
        String remainNote = remainder >= 0
                ? "いま手元に残るのは ¥%,d／月（%s%%）。ここから人件費を払います。"
                        .formatted(remainder, laborRow.percent())
                : "記録済みの費目だけで売上を上回っています（¥%,d の持ち出し）。"
                        .formatted(-remainder);

        return new Allocation(rows, targetBar, actualBar, rentNote, remainNote);
    }

    /** 実績の帯の 1 区画。マイナスの残り（持ち出し）は幅 0 で描く。 */
    private static AllocSeg seg(String cls, java.math.BigDecimal percent) {
        java.math.BigDecimal weight = (percent == null || percent.signum() < 0)
                ? java.math.BigDecimal.ZERO : percent;
        return new AllocSeg(cls, weight,
                weight.setScale(0, java.math.RoundingMode.HALF_UP) + "%");
    }

    /**
     * 賃貸の 1 行。
     *
     * <p>家賃が未設定（0）のときは、金額を書かずに「記録していない」として返します。
     * 0 円と出すと「家賃がかかっていない」の意味になってしまうためで、
     * これは人件費の行と同じ扱いです。
     */
    private static BreakdownRow rentRow(int monthlyRent, long sales) {
        if (monthlyRent <= 0) {
            return new BreakdownRow("賃貸", null, null, TARGET_RENT, "var(--border)", false, false);
        }
        BreakdownRow rent = row("賃貸", monthlyRent, sales, TARGET_RENT, "var(--surface)");
        // しるしの色は目標内なら薄いグレー、超えたら赤（設計の帯と同じ塗り分け）
        return rent.over()
                ? new BreakdownRow(rent.label(), rent.amount(), rent.percent(),
                        rent.target(), "var(--danger)", true, false)
                : rent;
    }

    private static BreakdownRow row(String label, int amount, long sales, int target, String color) {
        java.math.BigDecimal percent = (sales <= 0) ? null
                : java.math.BigDecimal.valueOf(amount)
                        .multiply(java.math.BigDecimal.valueOf(100))
                        .divide(java.math.BigDecimal.valueOf(sales), 1, java.math.RoundingMode.HALF_UP);
        return new BreakdownRow(label, amount, percent, target, color, true, false);
    }

    // ========================================================================
    //  注文されている商品
    // ========================================================================

    /** ランキング 1 行（構成比つき）。 */
    public record RankingRow(String name, String category, long quantity, long amount, java.math.BigDecimal share) {
    }

    private List<RankingRow> toRanking(List<ItemSales> ranking, long sales) {
        List<RankingRow> rows = new ArrayList<>();
        for (ItemSales i : ranking) {
            java.math.BigDecimal share = (sales <= 0) ? null
                    : java.math.BigDecimal.valueOf(i.sales())
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .divide(java.math.BigDecimal.valueOf(sales), 1, java.math.RoundingMode.HALF_UP);
            rows.add(new RankingRow(i.menuItemName(), i.categoryLabel(), i.qty(), i.sales(), share));
        }
        return rows;
    }

    // ========================================================================
    //  伝票（お会計）ベースの集計
    // ========================================================================

    /**
     * 1 営業日ぶんの「伝票ベース」の集計結果。
     *
     * <p>画面に出すためだけの入れ物なので record にしています。
     * record のアクセサは {@code getBills()} ではなく {@code bills()} なので、
     * テンプレートからは {@code ${billSummary.bills()}} のように括弧付きで呼びます。
     *
     * <p><b>金額を自分で足し算しているのは「伝票が持っている確定値」だけ</b>です。
     * 小計・チャージ・深夜料金の<b>計算式は {@code TableSession#recalculate} にしかありません</b>。
     * ここで割合を掛けたり足す順番を変えたりすると、
     * 画面の数字とレシートの数字が食い違う（＝金銭事故）ので絶対にしないでください。
     *
     * @param bills       会計済みの伝票の数（＝組数）
     * @param openBills   まだ会計していない伝票の数（この集計には入っていない）
     * @param guests      お客さまの人数の合計
     * @param total       ご請求額の合計
     * @param subtotal    小計（注文ぶん）の合計
     * @param tableCharge テーブルチャージの合計
     * @param lateNight   深夜料金の合計
     * @param tax         ご請求額に含まれる消費税の合計
     */
    public record BillSummary(long bills, long openBills, long guests, long total,
                              long subtotal, long tableCharge, long lateNight, long tax) {

        /** 客単価（お一人あたり）。人数 0 のときは 0（0 で割らない）。 */
        public long averagePerGuest() {
            return guests == 0 ? 0 : total / guests;
        }

        /** 組単価（1 伝票あたり）。0 組のときは 0。 */
        public long averagePerBill() {
            return bills == 0 ? 0 : total / bills;
        }
    }

    /**
     * その営業日の伝票を「会計済みのものだけ」集計する。
     *
     * <p>まだお会計していない伝票（OPEN）を混ぜてはいけません。
     * 金額が途中経過のうえ、そのあと追加注文が入れば変わるからです。
     * ただし「何組ぶんがまだ入っていないのか」は店長が知りたい情報なので、
     * 件数だけ数えて画面に出しています。
     *
     * <p>リポジトリには {@code summarizeClosed} という集計クエリもありますが、
     * 戻り値が {@code Object[]}（列が並んだだけの配列）で、
     * どの位置が何の金額かを人が覚えておかなければならず間違えやすいので使っていません。
     * 1 営業日ぶんの伝票はせいぜい数十件なので、Java 側で数えれば十分です。
     */
    private BillSummary summarizeBills(List<TableSession> sessions) {
        long bills = 0;
        long openBills = 0;
        long guests = 0;
        long total = 0;
        long subtotal = 0;
        long tableCharge = 0;
        long lateNight = 0;
        long tax = 0;

        for (TableSession bill : sessions) {
            if (bill.getStatus() != SessionStatus.CLOSED) {
                openBills++;
                continue;
            }
            bills++;
            guests += bill.getGuestCount();
            total += bill.getTotalAmount();
            subtotal += bill.getSubtotalAmount();
            tableCharge += bill.getTableChargeAmount();
            lateNight += bill.getLateNightAmount();
            tax += bill.getTaxAmount();
        }
        return new BillSummary(bills, openBills, guests, total, subtotal, tableCharge, lateNight, tax);
    }

    // ========================================================================
    //  表示用の組み立て
    // ========================================================================

    /**
     * 棒グラフ 1 本分のデータ。
     *
     * <p>画面に出すためだけの小さな入れ物なので record にしています。
     * record のアクセサは {@code getLabel()} ではなく {@code label()} なので、
     * テンプレートからは {@code ${b.label()}} のように括弧付きで呼びます。
     *
     * @param label         横軸のラベル（"8/16" や "11"）
     * @param value         金額（円）
     * @param heightPercent 棒の高さ（%）。最大値との比を Java 側で計算済み
     * @param title         マウスを載せたときに出す説明
     */
    public record BarPoint(String label, long value, int heightPercent, String title) {
    }

    /** 直近 n 日の推移を棒グラフ用に整える。 */
    private List<BarPoint> toDailyBars(List<DaySummary> series) {
        long max = 0;
        for (DaySummary d : series) {
            max = Math.max(max, d.amount());
        }

        List<BarPoint> bars = new ArrayList<>();
        for (DaySummary d : series) {
            bars.add(new BarPoint(
                    d.date().format(BAR_FORMAT),
                    d.amount(),
                    percentOf(d.amount(), max),
                    "%s ／ %d件 ／ %,d円".formatted(d.date().format(TITLE_FORMAT), d.orders(), d.amount())));
        }
        return bars;
    }

    /**
     * 時間帯別の売上を棒グラフ用に整える。
     *
     * <p>24 時間ぶんすべて並べるとラベルが潰れるので、
     * 営業時間帯を基本にしつつ、その外側でも売上がある時間は含める、という範囲にします。
     *
     * <p><b>「何時」ではなく「営業日の何番目の時間帯か」で並べる</b><br>
     * 深夜営業の店では、同じ営業日の中に 23 時と翌 0 時が同居します。
     * 単純に時刻の小さい順（0,1,…,23）に並べると、
     * <b>いちばん遅い時間帯の棒がグラフのいちばん左に来てしまい</b>、
     * 時間の流れが読めないグラフになります。
     * そこで、営業日の切り替え時刻（{@code businessDayCutoverHour}）を 0 番目とする
     * 通し位置に直してから並べます。
     * 深夜営業をしない店では、切り替え時刻をまたがないので並びは今までどおりです。
     */
    private List<BarPoint> toHourlyBars(Map<Integer, Long> hourly) {
        ShopSetting setting = shopSettingService.currentReadOnly();
        int cutover = setting.getBusinessDayCutoverHour();

        int from = positionOf(setting.getOpenTime().getHour(), cutover);
        int to = positionOf(setting.getCloseTime().getHour(), cutover);

        for (Map.Entry<Integer, Long> entry : hourly.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                int position = positionOf(entry.getKey(), cutover);
                from = Math.min(from, position);
                to = Math.max(to, position);
            }
        }
        if (to < from) {
            to = from;
        }

        long max = 0;
        for (int p = from; p <= to; p++) {
            max = Math.max(max, valueAt(hourly, hourAt(p, cutover)));
        }

        List<BarPoint> bars = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            int hour = hourAt(p, cutover);
            long amount = valueAt(hourly, hour);
            bars.add(new BarPoint(
                    String.valueOf(hour),
                    amount,
                    percentOf(amount, max),
                    "%d時台 ／ %,d円".formatted(hour, amount)));
        }
        return bars;
    }

    /**
     * 時刻（0〜23）を「営業日の何番目の時間帯か」（0〜23）に直す。
     * {@code Math.floorMod} を使うのは、引き算がマイナスになっても
     * 0〜23 に収めたいためです（{@code %} だとマイナスがそのまま残ります）。
     */
    private static int positionOf(int hour, int cutoverHour) {
        return Math.floorMod(hour - cutoverHour, 24);
    }

    /** {@link #positionOf} の逆変換。通し位置を実際の時刻に戻す。 */
    private static int hourAt(int position, int cutoverHour) {
        return Math.floorMod(cutoverHour + position, 24);
    }

    private long valueAt(Map<Integer, Long> hourly, int hour) {
        Long value = hourly.get(hour);
        return value == null ? 0L : value;
    }

    /**
     * 最大値に対する割合（%）。
     *
     * <p><b>ここが 0 除算の分かれ道</b>。
     * 売上がまったく無い日は max が 0 になり、そのまま割ると
     * {@code ArithmeticException: / by zero} で画面ごと落ちます。
     * 「まだ売上が無い」は異常ではなく普通に起きる状態なので、
     * 例外にせず高さ 0% を返します。
     */
    private int percentOf(long value, long max) {
        if (max <= 0 || value <= 0) {
            return 0;
        }
        return (int) Math.round(value * BAR_MAX_PERCENT / max);
    }
}
