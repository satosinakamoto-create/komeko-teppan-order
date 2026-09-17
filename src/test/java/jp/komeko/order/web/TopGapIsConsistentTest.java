package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 見出しの上の余白を全画面でそろえる（2026-09-17、店主の指摘）。
 *
 * <p>店主が挙げた画面——食材・在庫／レシピ・原価表／売上／バックアップ／
 * ダッシュボード——を実測したところ、<b>そのまま「上が 120px の組」</b>でした。
 * 他の 7 画面（商品・品切れ・カテゴリ・スタッフ・注文履歴・仕入れ・卓）は 88px です。
 *
 * <p>原因は 2 系統ありました。
 *
 * <h2>① 3 画面だけ本文の余白が 64px</h2>
 *
 * <pre>
 *   .theme-desk .staff-main:has(.inv-ingredients),
 *   .theme-desk .staff-main:has(.recipepage),
 *   .theme-desk .staff-main:has(.backuppage) { --main-pad-y: 64px; }
 * </pre>
 *
 * <p>根拠は<b>01 ページ（古い版）の採寸</b>でした。いま正とされている
 * 07 ページ（現行レイアウト × Render トンマナ）で測り直すと、
 * 食材 32 ／ レシピ 64 ／ バックアップ 64 ／ 商品 64 ／ 品切れ 32 ／ 売上 64 と
 * <b>設計そのものがばらついていて</b>、根拠になりません。
 * そろえるほうを採り、基準の 32px に統一します。
 *
 * <h2>② .sheet の打ち消しマージンが効いていなかった</h2>
 *
 * <p>ダッシュボードと売上は {@code <main class="sheet">} で、
 * {@code .sheet} は「本文の余白を打ち消して地を端まで敷く」ために
 * 負のマージンを持っています。ところが
 *
 * <pre>
 *   .theme-desk .staff-main > :first-child { margin-top: 0; }   (0,2,0)
 *   .sheet { margin: calc(var(--main-pad-y) * -1) ... }         (0,1,0)
 * </pre>
 *
 * <p>で上だけ打ち消され、<b>32px（本文）＋ 32px（.sheet 自身）で二重</b>に
 * なっていました。実測でも {@code margin-top: 0px} でした。
 * 地を端まで敷くという元の意図は正しいので、詳細度を上げて効かせます。
 */
@DisplayName("見出しの上の余白は全画面でそろっている")
class TopGapIsConsistentTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /**
     * ★ 本文の上下余白を画面ごとに変えない。
     *
     * <p>{@code --main-pad-y} を 32px 以外にしている画面が無いこと。
     * 狭い画面向けの上書き（メディアクエリ）は対象外です。
     */
    @Test
    @DisplayName("★ 本文の上下余白を 64px に広げている画面が無い")
    void noScreenWidensTheBodyPadding() throws Exception {
        String flat = css().replaceAll("(?s)@media[^{]*\\{.*?\\n\\}", "");

        assertThat(flat)
                .as("--main-pad-y を 64px にしている画面が残っている。"
                        + "食材・レシピ・バックアップだけ見出しが 32px 下がる")
                .doesNotContain("--main-pad-y: 64px");
    }

    /**
     * ★ {@code .sheet} の打ち消しが効く詳細度で書かれている。
     *
     * <p>{@code .theme-desk .staff-main > :first-child { margin-top: 0 }}（0,2,0）に
     * 勝てないと、ダッシュボードと売上だけ余白が二重になります。
     */
    @Test
    @DisplayName("★ .sheet の打ち消しマージンが効く（上の余白が二重にならない）")
    void theSheetCancelsTheBodyPadding() throws Exception {
        String css = css();

        int at = css.indexOf(".theme-desk .staff-main > main.sheet");
        assertThat(at)
                .as(".sheet の打ち消しを .theme-desk から書いていない。"
                        + "3410 行の :first-child { margin-top: 0 } に負けて上だけ打ち消せない")
                .isGreaterThan(0);

        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).as("上の打ち消しが無い")
                .contains("margin-top: calc(var(--main-pad-y) * -1)");
    }

    /**
     * ★ もとの {@code .sheet} は負のマージンを持ったまま。
     *
     * <p>左右の打ち消し（地を端まで敷く）はこちらが担っています。
     * 上書きのほうだけ見て、こちらを消してしまわないように。
     */
    @Test
    @DisplayName("★ .sheet 本体の左右の打ち消しは残っている")
    void theSheetKeepsItsHorizontalBleed() throws Exception {
        String css = css();
        int at = css.indexOf("\n.sheet {");
        assertThat(at).as(".sheet が無い").isGreaterThan(0);

        assertThat(css.substring(at, css.indexOf("}", at)))
                .as("左右の打ち消しまで消えている。地が端まで届かなくなる")
                .contains("var(--main-pad-x) * -1");
    }
}
