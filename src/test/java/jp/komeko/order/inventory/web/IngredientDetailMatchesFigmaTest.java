package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食材の詳細を Figma「ト04c 食材の詳細（トンマナRender）」840:9983 に合わせる（2026-09-14）。
 *
 * <p><b>なぜこのテストを足したか。</b>
 * 店主から「07 ページの Figma が踏襲されていない」と指摘されました。
 * 寸法（帯の高さ・余白）は合わせてあったのに、<b>中身が別物</b>だったためです。
 * 寸法だけ見て「追従した」と扱わないよう、ここでは<b>何が並んでいるか</b>を見ます。
 *
 * <p><b>Figma の数字帯（840:10125）の実測</b>
 * <pre>
 *   横並び・中央ぞろえ・gap 48 ／ padding 16px 24px ／ 地は白・枠 #e8e8e8
 *     「いまの在庫」14px #828282  ＋ 「2,450 g」  24px Bold #1c1c1c
 *     「あと」      14px #828282  ＋ 「3 営業日」 24px Bold #d33f3f（赤）
 *     「警告残量」  14px #828282  ＋ 「500 g」    24px Bold #1c1c1c
 *     のばす
 *     「理論値です。棚卸しで実測に合わせます」12px #828282
 * </pre>
 *
 * <p><b>実装はここが違っていた</b>
 * <ol>
 *   <li>数字帯の中身が「いまの残量／単価（税込）／単価（税抜）」の 3 枚だった。
 *       Figma は在庫・もち日数・警告残量。<b>単価は見出しの補足へ移す</b>
 *       （Figma の補足は「g ／ 野菜 ／ 単価 0.12 円/g」）</li>
 *   <li>「あと◯営業日」が詳細に無かった。一覧（{@code ingredients.html} の「あと」列）
 *       には出しているのに、1 品を開いたときだけ読めない状態だった。
 *       {@code level.daysLeft()} は既にモデルに来ているので、出すだけで足りる</li>
 *   <li>警告残量が「食材の設定」フォームの入力欄にしか無く、<b>読むための表示が無かった</b></li>
 * </ol>
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("食材の詳細は Figma ト04c どおり")
class IngredientDetailMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/ingredient-detail.html");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /**
     * コメントを落とした HTML。<b>doesNotContain は必ずこちらに掛けること。</b>
     *
     * <p>このプロジェクトは「なぜそう書くか」を注意書きに残す方針なので、
     * 禁止したい文字列がそのまま注意書きに出てきます。
     * {@code PageHeadMatchesFigmaTest} が実際にこれで落ちました。
     */
    private String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    // ---------------------------------------------------------------- 数字帯

    @Test
    @DisplayName("★ 数字帯は「いまの在庫／あと◯営業日／警告残量」の 3 つ")
    void theStatLineShowsWhatFigmaShows() throws Exception {
        String m = withoutComments(read(TPL));

        assertThat(m).as("数字帯（.statline）が無い").contains("class=\"statline\"");
        assertThat(m).as("「いまの在庫」が無い").contains("いまの在庫");
        assertThat(m).as("「あと」（もち日数）が無い").contains("statline__label\">あと<");
        assertThat(m).as("「警告残量」の表示が無い").contains("警告残量");

        // もち日数は level.daysLeft() をそのまま読む。一覧と同じ数字でなければ意味がない
        assertThat(m).as("daysLeft を読んでいない").contains("level.daysLeft()");
        // 警告残量は食材マスタの値。設定フォームの input ではなく、読むための表示
        assertThat(m).as("lowThresholdQty を読んでいない").contains("lowThresholdQty");
    }

    @Test
    @DisplayName("★ 単価は数字帯から見出しの補足へ移す（Figma は「g ／ 野菜 ／ 単価 0.12 円/g」）")
    void theUnitCostMovesIntoTheSubtitle() throws Exception {
        String m = withoutComments(read(TPL));

        int head = m.indexOf("page-head__sub");
        assertThat(head).as("見出しの補足が無い").isGreaterThan(0);
        String sub = m.substring(head, m.indexOf("</span>", head));
        assertThat(sub).as("見出しの補足に単価が無い").contains("単価");

        // 数字帯に単価のカードが残っていないこと
        int line = m.indexOf("class=\"statline\"");
        String band = m.substring(line, m.indexOf("</div>\n\n", line) > 0
                ? m.indexOf("この数字の内訳") : m.length());
        assertThat(band).as("数字帯に単価（税抜）が残っている").doesNotContain("単価（税抜）");
    }

    @Test
    @DisplayName("★ 帯の右端に「理論値です。棚卸しで実測に合わせます」")
    void theBandCarriesItsCaveat() throws Exception {
        assertThat(withoutComments(read(TPL)))
                .as("理論値であることの注記が無い")
                .contains("理論値です。棚卸しで実測に合わせます");
    }

    @Test
    @DisplayName("★ もちが短い日数は赤（Figma の「3 営業日」は #d33f3f）")
    void theShortForecastTurnsRed() throws Exception {
        assertThat(withoutComments(read(TPL)))
                .as("もち日数に危険色の切り替えが無い")
                .contains("statline__value--danger");
    }

    // ---------------------------------------------------------------- CSS

    /**
     * Figma の数字帯は「カードを 3 枚並べたもの」ではなく<b>1 本の帯</b>です。
     * 既存の {@code .stat}（grid--3 に 3 枚並べる）では作れないので、
     * このクラスだけ新しく足しました（CLAUDE.md「既存クラスの組み合わせで
     * 足りないときだけ追加する」に沿った判断）。
     */
    @Test
    @DisplayName("★ .statline の寸法は Figma どおり（gap 48・padding 16/24）")
    void theStatLineMatchesItsFrame() throws Exception {
        String css = read(CSS);
        int at = css.indexOf(".statline {");
        assertThat(at).as(".statline が app.css に無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("display: flex;");
        assertThat(rule).contains("gap: 48px;");
        assertThat(rule).contains("padding: 16px 24px;");
        assertThat(rule).contains("align-items: center;");
    }

    @Test
    @DisplayName("★ 数字は 24px Bold・ラベルは 14px（ホールの 20/16 とは別の型）")
    void theNumbersUseTheFigmaType() throws Exception {
        String css = read(CSS);

        int label = css.indexOf(".statline__label {");
        assertThat(label).as(".statline__label が無い").isGreaterThan(0);
        assertThat(css.substring(label, css.indexOf("}", label))).contains("font-size: 14px;");

        int value = css.indexOf(".statline__value {");
        assertThat(value).as(".statline__value が無い").isGreaterThan(0);
        String v = css.substring(value, css.indexOf("}", value));
        assertThat(v).contains("font-size: 24px;");
        assertThat(v).contains("font-weight: 700;");
    }
}
