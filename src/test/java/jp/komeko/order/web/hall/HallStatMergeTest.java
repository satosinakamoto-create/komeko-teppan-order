package jp.komeko.order.web.hall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ホールの数字カード 3 枚をレーン見出しへ畳み込む（2026-09-14、店主の指摘）。
 *
 * <p><b>店主の言葉</b>「在席・未提供・空席の 3 つの情報って分けずに
 * 在卓、お会計待ち、バッシングでまとめられるよね」。
 *
 * <p>数字を突き合わせると、たしかにほぼ二重でした。
 * <pre>
 *   在席 6卓／13名 … 在卓＋お会計待ちの合計。レーン見出しに人数を足せば同じ
 *   未提供 6件     … 上の帯に同じチップが常に出ている（押すと厨房へ飛ぶ）
 *   空席 1卓       … これだけ行き場が無い → 新規お客様ボタンの脇へ
 * </pre>
 *
 * <p>カードの段が消えるぶん、盤面が 1 画面に入る量が増えます
 * （iPad で伝票 2 枚が折り目の上に収まるようになった）。
 *
 * <p><b>Figma を先に直してある</b>：現02 542:3705／ト02 725:2103／
 * トi02 735:14980／トp02 735:15203。実装はそれに追従するだけ。
 *
 * <p><b>ここで守るもの</b>
 * <ol>
 *   <li>数字カードの段（.grid--3 ＋ .stat）を復活させない</li>
 *   <li>レーン見出しは「4卓 ／ 9名」の形。卓数だけに戻すと
 *       「何名ぶんの会計が残っているか」が読めなくなる</li>
 *   <li>バッシングに名数は付けない（もう会計が済んでいて、人数に意味が無い）</li>
 *   <li>空席は使う場所（ご案内ボタン）の脇。カードに戻さない</li>
 * </ol>
 */
@DisplayName("ホールの数字カードはレーン見出しへ畳み込む")
class HallStatMergeTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/hall/board.html");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n");
    }

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** 注意書きに一致して落ちる罠（PageHeadMatchesFigmaTest と同じ）を避ける。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("★ 数字カードの段はもう無い（在席・未提供・空席の 3 枚）")
    void theStatRowIsGone() throws Exception {
        String t = withoutComments(tpl());
        assertThat(t).doesNotContain("class=\"stat\"");
        assertThat(t).doesNotContain("${occupiedCount}");
        assertThat(t).doesNotContain("${pendingCount}");
    }

    /**
     * ★ 2026-09-14 に形が変わった：文字列の連結 → 数字を {@code <b>} で包む。
     *
     * <p>畳んだ直後は「4卓 ／ 9名」を 1 本の文字列（13〜14px の灰色）で
     * 出していたが、店主から「読みにくい。重要な情報だし」。
     * 旧カードは数字が 28px だった——段を畳んだとき、
     * <b>「遠くから読める大きさ」まで一緒に捨てていた</b>。
     * 数字だけ 18px の濃色に戻し、単位（卓・名）は 14px の灰色のまま置く。
     * CSS で数字と単位を別に描くため、数字を b で包む必要がある。
     */
    @Test
    @DisplayName("★ 在卓とお会計待ちの見出しは「卓数 ／ 名数」。数字は b で強調")
    void theLaneHeadsCarryTheGuestCounts() throws Exception {
        String t = tpl();
        // 在卓
        assertThat(t).contains("<b th:text=\"${seatedCount}\">4</b>卓 ／ <b th:text=\"${seatedGuests}\">9</b>名");
        // お会計待ち
        assertThat(t).contains("<b th:text=\"${closingCount}\">2</b>卓 ／ <b th:text=\"${closingGuests}\">5</b>名");
        // バッシングは卓数だけ（名数に意味が無い）
        assertThat(t).contains("<b th:text=\"${cleanupCount}\">3</b>卓");
    }

    /**
     * ★ 大きさは 2 回上げた。
     * <pre>
     *   09-14 朝  数字 18px／単位 14px … 太さと濃さだけで読ませようとした
     *   09-14 夜  数字 20px／単位 16px … 「余白だけ大きくなって文字そのまま」
     * </pre>
     * 1 回目は数字を太く濃くしただけで、単位は 14px のまま置いた。
     * ピル（の器）だけ数字につられて育ち、<b>文字が大きくなった感じがしない</b>
     * という指摘はそのとおりだった。単位ごと一段上げる。
     */
    @Test
    @DisplayName("★ 数字 20px 濃色・単位 16px 灰色（旧カードの 28px の代わり）")
    void theNumbersReadFromADistance() throws Exception {
        String css = css();
        assertThat(css).contains(".hallboard .lane__head .count b { font-size: 20px; font-weight: 700; color: #1c1c1c; }");
        // 単位は静かに。ピルの土台のほうは 400 の灰色、ただし大きさは 16px
        int at = css.indexOf(".hallboard .lane__head .count {");
        assertThat(at).isGreaterThan(0);
        String pill = css.substring(at, css.indexOf("}", at));
        assertThat(pill).contains("font-size: 16px;");
        assertThat(pill).contains("font-weight: 400;");
        assertThat(pill).contains("color: #828282;");
        // 空席も同じ扱い
        // 色も明示する。.section-title__count の既定は黒 38%（白地で ≒#9e9e9e）で、
        // Figma の #828282 より一段薄い
        assertThat(css).contains(".hallboard .spacer + .section-title__count { font-size: 16px; color: #828282; }");
        assertThat(css).contains(".hallboard .spacer + .section-title__count b { font-size: 20px; font-weight: 700; color: #1c1c1c; }");
    }

    @Test
    @DisplayName("★ 空席は新規お客様ボタンのすぐ左。数字は b で強調")
    void theVacancySitsNextToTheSeatButton() throws Exception {
        String t = withoutComments(tpl());
        int vacant = t.indexOf("空席 <b th:text=\"${vacantCount}\">");
        int button = t.indexOf("新規お客様");
        assertThat(vacant).as("空席の表示が見出しの帯に無い").isGreaterThan(0);
        assertThat(button).isGreaterThan(vacant);
        // 同じ見出しの帯の中にあること（間に別のブロックが挟まっていない）
        assertThat(t.substring(vacant, button)).doesNotContain("</div>");
    }

    /**
     * バッシングカードの説明文 → 席数（2026-09-14、店主）。
     *
     * <p>「片付けがすんだら、次の組をご案内できます」は
     * <b>当たり前すぎて何も言っていない</b>うえ、カードの数だけ繰り返される。
     * 代わりにその卓の席数を出す。待っているお客さまの人数と突き合わせて
     * 「2名待ってるからこの 4名席 を先に片付けるか」を<b>選べる</b>情報にする。
     *
     * <p>経過時間（いつからバッシング待ちか）は入れないと決めた。
     * 溜まってきたら処理すればいいだけで、順番の判断には席数のほうが効く。
     */
    @Test
    @DisplayName("★ バッシングカードは説明文ではなく席数")
    void theCleanupCardShowsTheCapacityInsteadOfTheTruism() throws Exception {
        String t = withoutComments(tpl());
        assertThat(t).doesNotContain("片付けがすんだら");
        assertThat(t).contains("<b th:text=\"${table.capacity}\">4</b>名席");
        // 数字は一段大きく濃く（レーン見出し・空席と同じ 20px。
        // 単位 16px はカードの土台 14px より上げるので、席数の行だけ別に指定する）
        String css = css();
        assertThat(css).contains(".hallboard .billcard--cleanup .billcard__meta { font-size: 16px; }");
        assertThat(css).contains(
                ".hallboard .billcard--cleanup .billcard__meta b { font-size: 20px; font-weight: 700; color: #1c1c1c; }");
    }

    @Test
    @DisplayName("★ 盤面の上の 32px はもう無い（数字カードの段が消えたので gap 24 だけ）")
    void theBoardNoLongerNeedsExtraRoom() throws Exception {
        String css = css();
        int at = css.indexOf(".theme-desk .hallboard .board,");
        assertThat(at).as("ホールの盤面の指定が無い").isGreaterThan(0);
        assertThat(withoutComments(css.substring(at, css.indexOf("}", at))))
                .doesNotContain("margin-top");
    }

    @Test
    @DisplayName("★ 数字カード用の CSS も残さない（死んだ規則は次に読む人を騙す）")
    void theDeadStatRulesAreRemoved() throws Exception {
        String bare = withoutComments(css());
        assertThat(bare).doesNotContain(".theme-desk .hallboard .stat ");
        assertThat(bare).doesNotContain(".theme-desk .hallboard .stat__value");
        assertThat(bare).doesNotContain(".hallboard .grid--3");
    }
}
