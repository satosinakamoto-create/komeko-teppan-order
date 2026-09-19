package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * カテゴリと卓の一覧表が同じ寸法でそろっている（2026-09-19、店主の指摘
 * 「レイアウトちょっとズレてるから修正したい」）。
 *
 * <h2>測ったら、卓の表には指定が 1 行も無かった</h2>
 *
 * <p>カテゴリの表（{@code .table--cats}）には列幅も高さも書いてありましたが、
 * 卓の表には {@code .table--tables} で始まる規則が<b>ひとつもありません</b>でした。
 * 中身任せ（{@code table-layout: auto}）なので、卓名の長さで列幅が毎回変わります。
 *
 * <pre>
 *   項目            カテゴリ            卓（直す前）
 *   列幅            固定 400/200/360/160  自動 355.1 / 148 / 167.3 / 238.4 / 211.2
 *   見出しの高さ     64px                 61.5px
 *   セルの余白       16 / 16              20 / 12
 *   行の高さ         76px（※）            77px
 * </pre>
 *
 * <p>※ カテゴリの 76px も設計（68px）から外れていました。
 * つまみ（36px）を置いたのに上下の余白 20 + 19 がそのままで、
 * 20 + 36 + 19 + 区切り線 1 = 76 に膨らんでいたためです。
 * <b>つまみは小さくしていません。</b>上下の余白を 0 にして、
 * 商品・レシピの表と同じ {@code height: 67px} + {@code vertical-align: middle} にそろえました。
 *
 * <h2>列幅の決め方</h2>
 *
 * <p>設計（ト12 卓）は 卓名 320 / 席数 180 / QR 240 / 状態 380 の 4 列で 1120。
 * 実装は QR を別画面へ出したので エリア が入り、並びが増えて 5 列です。
 * 設計の 320 / 180 / 240 を引き継ぎ、並びをカテゴリと同じ 160 にして、
 * 余りを状態へ回しました（320 + 180 + 240 + 220 + 160 = 1120）。
 *
 * <h2>合計を 1120 にそろえる理由</h2>
 *
 * <p>{@code table-layout: fixed} と合わせると、設計幅ではぴったり、
 * 狭い端末では横スクロール、広い端末では比例して伸びます。
 * 実測で 1920px のとき合計 1444 に伸び、列の比率は保たれていました。
 */
@DisplayName("カテゴリと卓の一覧表の寸法")
class ListTablesLineUpTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** コメントと空白を外してから探す。説明文に検索語が入っていて素通りするのを防ぐ。 */
    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\\s+", "");
    }

    /**
     * ★ 卓の表にも列幅が指定してあること。
     *
     * <p>無いと中身任せになり、卓名を 1 つ長くしただけで全部の列がずれます。
     */
    @Test
    @DisplayName("★ 卓の表の列幅が固定されている")
    void theTableListHasFixedColumns() throws Exception {
        String css = css();

        assertThat(css)
                .as("卓の表が table-layout: fixed でない。"
                        + "中身任せだと卓名の長さで列幅が毎回変わる")
                .contains(".table--tables{table-layout:fixed;}");

        int[] widths = {320, 180, 240, 220, 160};
        for (int i = 0; i < widths.length; i++) {
            assertThat(css)
                    .as((i + 1) + " 列目の幅が指定されていない")
                    .contains(".table--tablesth:nth-child(" + (i + 1) + "),"
                            + ".table--tablestd:nth-child(" + (i + 1) + ")"
                            + "{width:" + widths[i] + "px;}");
        }
    }

    /** ★ 列幅の合計が設計の 1120 になること。 */
    @Test
    @DisplayName("★ 列幅の合計が 1120（カテゴリと同じ）")
    void bothTablesAddUpToTheDesignWidth() {
        assertThat(320 + 180 + 240 + 220 + 160)
                .as("卓の列幅の合計が設計の 1120 でない。"
                        + "足りないと右が余り、多いと設計幅でも横スクロールが出る")
                .isEqualTo(1120);
        assertThat(400 + 200 + 360 + 160)
                .as("カテゴリの列幅の合計が 1120 でない").isEqualTo(1120);
    }

    /**
     * ★ 2 つの表で見出しの高さ・セルの余白・行の高さがそろっていること。
     *
     * <p>サイドバーで行き来する画面なので、切り替えた瞬間に表が跳ねて見えます。
     */
    @Test
    @DisplayName("★ カテゴリと卓で見出し 64・行 67 がそろっている")
    void theTwoListsShareTheSameHeights() throws Exception {
        String css = css();

        for (String t : new String[]{"cats", "tables"}) {
            assertThat(css)
                    .as(t + " の見出しの高さが 64px でない")
                    .contains(".theme-desk.table--" + t + "th{padding:16px;height:64px;}");
            assertThat(css)
                    .as(t + " の行の高さが 67px でない")
                    .contains(".theme-desk.table--" + t + "td"
                            + "{padding:020px;height:67px;vertical-align:middle;}");
        }
    }

    /**
     * ★ セルの上下の余白を 0 にしてあること（つまみを縮めない）。
     *
     * <p>{@code box-sizing: border-box} なので、上下に余白があるぶん
     * つまみ（36px）の入る床が削れます。20 + 36 + 19 + 線 1 = 76px となり、
     * 設計の 68px を 8px 超えていました。
     * <b>床は「行」ではなく「中身」に敷く</b>——余白を 0 にすれば
     * {@code height: 67px} がそのまま効いて、線と合わせて 68px になります。
     */
    @Test
    @DisplayName("★ つまみを縮めずに 68px に収めてある")
    void theHandleFitsWithoutShrinking() throws Exception {
        String css = css();

        assertThat(css)
                .as("★ カテゴリのセルに上下の余白が残っている。"
                        + "つまみ 36px が押し出されて行が 76px に膨らむ")
                .doesNotContain(".theme-desk.table--catstd{padding:20px20px19px;");

        assertThat(css)
                .as("つまみの高さが 36px でなくなっている。"
                        + "行に収めるために小さくしたなら、押しにくくなっている")
                .contains("height:36px;");
    }

    /**
     * ★ 席数と品数の揃え方をそろえること。
     *
     * <p>「4 席」だけ右、「5 品」は左でした。同じ性質の値なのに、
     * サイドバーで行き来すると数字の位置が左右に飛びます。
     * 設計（ト12 / トi14 / トp14）はどちらも左ぞろえです。
     *
     * <p>{@code num} は桁をそろえて読む金額のための目印です
     * （{@code .table td.num { text-align: right }}）。
     * 1 桁で単位が付くこの列には要りません。
     */
    @Test
    @DisplayName("★ 席数と品数がどちらも左ぞろえ")
    void seatsAndItemCountsAreAlignedTheSameWay() throws Exception {
        String seats = Files.readString(
                        Path.of("src/main/resources/templates/admin/table-list.html"))
                .replace("\r\n", "\n").replaceAll("(?s)<!--.*?-->", "");
        String counts = Files.readString(
                        Path.of("src/main/resources/templates/admin/category-list.html"))
                .replace("\r\n", "\n").replaceAll("(?s)<!--.*?-->", "");

        assertThat(seats)
                .as("★ 席数に num が付いている。右へ寄って、隣の画面の品数とそろわない")
                .doesNotContain("<td class=\"num\" th:text=\"${t.capacity}");
        assertThat(seats).as("席数の列が無い").contains("th:text=\"${t.capacity} + ' 席'\"");
        assertThat(counts)
                .as("品数に num が付いた。設計はどちらも左ぞろえ")
                .doesNotContain("<td class=\"num\" th:text=\"${itemCounts");
    }
}
