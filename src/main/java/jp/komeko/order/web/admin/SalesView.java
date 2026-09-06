package jp.komeko.order.web.admin;

import jp.komeko.order.service.dto.ItemSales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 売上まわりの「画面に出す形」を作る道具箱。
 *
 * <p>売上（月ごと）とダッシュボード（今日）が<b>同じ見た目</b>なので、
 * 折れ線の座標計算とランキングの構成比を 1 か所に置いています。
 * 2 つの画面に同じ計算を書くと、片方だけ直したときに
 * 「同じグラフのはずなのに目盛りが違う」という形でずれます。
 *
 * <p><b>グラフを外部ライブラリなしで描く</b><br>
 * 店内の PC はインターネットに繋がっていないことがあるので、
 * CDN からグラフライブラリを読み込む作りにはしません。
 * 素の SVG に描き、<b>座標はすべてここで計算して</b>テンプレートへ渡します。
 * テンプレートに割り算を持ち込むと、0 除算やまるめの都合が
 * HTML の中に散らばって直せなくなります。
 *
 * <p>SVG の座標系は 0〜1000 × 0〜300 に固定し、表示側は {@code viewBox} で伸縮させます。
 * これなら画面幅が変わってもここの計算は変わりません。
 */
public final class SalesView {

    private SalesView() {
    }

    /** グラフの内側の寸法（SVG のローカル座標）。 */
    private static final int CHART_W = 1000;
    private static final int CHART_H = 300;
    /** 左は金額ラベル、下は月ラベルのために空ける。 */
    private static final int PAD_LEFT = 56;
    private static final int PAD_RIGHT = 8;
    private static final int PAD_TOP = 16;
    private static final int PAD_BOTTOM = 28;

    /**
     * 折れ線グラフ 1 枚ぶん。
     *
     * @param points 各点（ラベルつき）
     * @param line   折れ線の points 属性（"x,y x,y ..."）
     * @param area   線の下を塗る多角形の points 属性
     * @param grid   横罫線の y 座標と目盛りラベル
     */
    public record Chart(List<ChartPoint> points, String line, String area, List<GridLine> grid) {
    }

    /**
     * 折れ線の点 1 つ。{@code last} は右端（＝いま見ている期間）かどうか。
     *
     * <p>{@code peak} はいちばん高い点（最初の 1 つだけ）。
     * ダッシュボードの時間帯グラフは、設計（01 ダッシュボード 15:319）が
     * <b>ピークにだけ金額を添える</b>形なので、その目印に使います。
     * 全部の点に金額を書くと、1 時間刻み（点が 10 個）では文字が重なります。
     */
    public record ChartPoint(String label, long value, String amountLabel,
                             int x, int y, boolean last, boolean peak) {
    }

    /** 横罫線 1 本。 */
    public record GridLine(int y, String label) {
    }

    /**
     * 折れ線を組み立てる。
     *
     * @param labels 横軸のラベル（"8月" や "9/3"）
     * @param values 各点の金額
     */
    public static Chart chart(List<String> labels, List<Long> values) {
        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        // 目盛りは「40万ごと」のようなキリのいい幅にする。
        // 実データの最大値をそのまま天井にすると、期間を変えるたびに目盛りが動いて
        // 前に見たグラフと見比べられなくなる。
        long step = niceStep(max);
        long top = step * 4;

        List<GridLine> grid = new ArrayList<>();
        for (int i = 4; i >= 0; i--) {
            long v = step * i;
            grid.add(new GridLine(yOf(v, top), axisLabel(v)));
        }

        // ピーク（最初の最大値）の位置。売上が全く無いときはピーク無し
        int peakIndex = -1;
        if (max > 0) {
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) == max) {
                    peakIndex = i;
                    break;
                }
            }
        }

        List<ChartPoint> points = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int n = labels.size();
        for (int i = 0; i < n; i++) {
            long v = values.get(i);
            int x = (n == 1)
                    ? PAD_LEFT + (CHART_W - PAD_LEFT - PAD_RIGHT) / 2
                    : PAD_LEFT + (CHART_W - PAD_LEFT - PAD_RIGHT) * i / (n - 1);
            int y = yOf(v, top);
            points.add(new ChartPoint(labels.get(i), v, manLabel(v), x, y,
                    i == n - 1, i == peakIndex));
            line.append(x).append(',').append(y).append(' ');
        }

        String area = "";
        if (!points.isEmpty()) {
            int baseY = CHART_H - PAD_BOTTOM;
            area = points.get(0).x() + "," + baseY + " " + line.toString().trim()
                    + " " + points.get(points.size() - 1).x() + "," + baseY;
        }
        return new Chart(points, line.toString().trim(), area, grid);
    }

    /**
     * 時間帯別（今日 1 日ぶん）の折れ線。ダッシュボード用。
     *
     * <p>設計（01 ダッシュボード 15:319）は「直近 7 日」ではなく
     * <b>今日の時間帯別</b>を折れ線で見せる形です。
     * 開店〜閉店の時間帯を基本にしつつ、その外側でも売上のある時間は含めます
     * （閉店後の会計などがこぼれると、合計と目で合わなくなる）。
     *
     * <p><b>「何時」ではなく「営業日の何番目の時間帯か」で並べます。</b>
     * 深夜営業では同じ営業日に 23 時と翌 0 時が同居するので、
     * 時刻の小さい順に並べると閉店間際の棒がグラフの左端に来てしまいます。
     * 営業日の切り替え時刻（cutoverHour）を 0 番目とする通し位置に直してから並べます。
     *
     * @param hourly      時刻（0〜23）→ 金額。無い時刻は 0 扱い
     * @param openHour    開店時刻の「時」
     * @param closeHour   閉店時刻の「時」
     * @param cutoverHour 営業日の切り替え時刻
     */
    public static Chart hourlyChart(Map<Integer, Long> hourly,
                                    int openHour, int closeHour, int cutoverHour) {
        int from = positionOf(openHour, cutoverHour);
        int to = positionOf(closeHour, cutoverHour);
        for (Map.Entry<Integer, Long> entry : hourly.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                int position = positionOf(entry.getKey(), cutoverHour);
                from = Math.min(from, position);
                to = Math.max(to, position);
            }
        }
        if (to < from) {
            to = from;
        }

        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            int hour = Math.floorMod(cutoverHour + p, 24);
            Long v = hourly.get(hour);
            labels.add(hour + "時");
            values.add(v == null ? 0L : v);
        }
        return chart(labels, values);
    }

    /**
     * 時刻（0〜23）を「営業日の何番目の時間帯か」（0〜23）に直す。
     * {@code Math.floorMod} を使うのは、引き算がマイナスになっても
     * 0〜23 に収めたいため（{@code %} だとマイナスがそのまま残る）。
     */
    private static int positionOf(int hour, int cutoverHour) {
        return Math.floorMod(hour - cutoverHour, 24);
    }

    private static int yOf(long value, long top) {
        int usable = CHART_H - PAD_TOP - PAD_BOTTOM;
        if (top <= 0) {
            return CHART_H - PAD_BOTTOM;
        }
        return (int) (CHART_H - PAD_BOTTOM - Math.round((double) value / top * usable));
    }

    /** 目盛り 1 段ぶんの金額。10 万・20 万・40 万… とキリのいい数だけを使う。 */
    private static long niceStep(long max) {
        if (max <= 0) {
            return 25_000;
        }
        long rough = max / 4;
        long[] candidates = {5_000, 10_000, 25_000, 50_000, 100_000, 200_000,
                400_000, 500_000, 1_000_000, 2_000_000, 5_000_000};
        for (long c : candidates) {
            if (rough <= c) {
                return c;
            }
        }
        return 10_000_000;
    }

    /**
     * <b>目盛り（横罫線）の金額表示。丸めません。</b>
     *
     * <p>目盛りは軸の値そのものなので、点の注記と違って丸めてはいけません。
     * もとは {@link #manLabel} を使っていましたが、あちらは万未満を四捨五入するため
     * <b>罫線の値と表示が食い違っていました</b>（2026-09-05 に修正）。
     *
     * <p>{@code niceStep} の候補には 5,000 と 25,000 があり、
     * この 2 つは 0.5 万刻みの罫線を作ります。すると
     * <ul>
     *   <li>¥25,000 の罫線 → 「¥3万」（実際より 5,000 円高く見える）</li>
     *   <li>¥75,000 の罫線 → 「¥8万」（同上）</li>
     *   <li>¥15,000 と ¥20,000 の罫線が<b>どちらも「¥2万」</b>になる</li>
     * </ul>
     * となり、その罫線を頼りに点を読むと ¥25,000 を ¥30,000 と読み違えます。
     * 売上がまだ無い月（max=0 → step=25,000）でも必ず再現していました。
     *
     * <p>割り切れるときは「¥5万」、割り切れないときだけ「¥2.5万」と小数を出します。
     * 1 万円未満は素の金額です。
     */
    private static String axisLabel(long yen) {
        if (yen == 0) {
            return "¥0";
        }
        if (yen < 10_000) {
            return "¥" + String.format("%,d", yen);
        }
        if (yen % 10_000 == 0) {
            return "¥" + (yen / 10_000) + "万";
        }
        if (yen % 1_000 == 0) {
            return "¥" + (yen / 10_000) + "." + ((yen % 10_000) / 1_000) + "万";
        }
        return "¥" + String.format("%,d", yen);
    }

    /**
     * 点に添える金額の表示。<b>こちらは読みやすさを優先して丸めます。</b>
     *
     * <p>1 万円以上は「¥128万」。桁を数えずに大小が読めます。
     * 日ごとのグラフだと 1 万円未満の日もあるので、そこは素の金額で出します。
     *
     * <p>目盛り（軸の値）には使わないこと。丸めが軸の嘘になります（{@link #axisLabel}）。
     */
    private static String manLabel(long yen) {
        if (yen == 0) {
            return "¥0";
        }
        if (yen >= 10_000) {
            return "¥" + Math.round(yen / 10_000.0) + "万";
        }
        return "¥" + String.format("%,d", yen);
    }

    // ========================================================================
    //  注文されている商品
    // ========================================================================

    /** ランキング 1 行（構成比つき）。 */
    public record RankingRow(String name, String category, long quantity, long amount, BigDecimal share) {
    }

    public static List<RankingRow> ranking(List<ItemSales> ranking, long sales) {
        List<RankingRow> rows = new ArrayList<>();
        for (ItemSales i : ranking) {
            rows.add(new RankingRow(i.menuItemName(), i.categoryLabel(), i.qty(), i.sales(), percent(i.sales(), sales)));
        }
        return rows;
    }

    // ========================================================================
    //  比率
    // ========================================================================

    /**
     * 割合（%）。分母が 0 以下なら null。
     *
     * <p>null を返すのは、0 で割れないからです。
     * ここで 0% を返すと「構成比 0%」という嘘の数字が画面に出ます。
     */
    public static BigDecimal percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /**
     * 前の期間との差（%）。前が 0 なら null。
     *
     * <p>0 からの伸びを「＋100%」と書くと、1 円でも売れた月が
     * 満点のように見えてしまいます。比べられないときは比べない。
     */
    public static BigDecimal deltaPercent(long now, long prev) {
        if (prev <= 0) {
            return null;
        }
        return BigDecimal.valueOf(now - prev)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(prev), 1, RoundingMode.HALF_UP);
    }
}
