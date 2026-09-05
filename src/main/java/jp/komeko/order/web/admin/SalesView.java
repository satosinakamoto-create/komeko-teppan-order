package jp.komeko.order.web.admin;

import jp.komeko.order.service.dto.ItemSales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

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

    /** 折れ線の点 1 つ。{@code last} は右端（＝いま見ている期間）かどうか。 */
    public record ChartPoint(String label, long value, String amountLabel,
                             int x, int y, boolean last) {
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
            grid.add(new GridLine(yOf(v, top), manLabel(v)));
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
            points.add(new ChartPoint(labels.get(i), v, manLabel(v), x, y, i == n - 1));
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
     * 目盛りの金額表示。
     *
     * <p>1 万円以上は「¥128万」。桁を数えずに大小が読めます。
     * 日ごとのグラフだと 1 万円未満の日もあるので、そこは素の金額で出します。
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
    public record RankingRow(String name, long quantity, long amount, BigDecimal share) {
    }

    public static List<RankingRow> ranking(List<ItemSales> ranking, long sales) {
        List<RankingRow> rows = new ArrayList<>();
        for (ItemSales i : ranking) {
            rows.add(new RankingRow(i.menuItemName(), i.qty(), i.sales(), percent(i.sales(), sales)));
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
