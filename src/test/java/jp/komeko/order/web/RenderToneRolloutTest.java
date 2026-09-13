package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Figma 07「現行レイアウト × Render トンマナ」で店主承認した残りを実装に入れる（2026-09-13）。
 *
 * <p>ダッシュボード・売上・表の 3 つは先に入れてあるので、ここで残るのは 4 点です。
 * <ol>
 *   <li>サイドバーを畳む条件を<b>全画面</b>に広げる（盤面だけだったのをやめる）</li>
 *   <li>タブの角を落とす（箱の形と 136×56 は現行のまま）</li>
 *   <li>探す欄の角を落とす</li>
 *   <li>札（.badge）の角を落とす</li>
 * </ol>
 *
 * <p><b>1 はレイアウトが動きます。</b>
 * 1025〜1380px のノート PC でも管理画面のサイドバーが 64px に畳まれます。
 * iPad（1024）で本文が 712 → 912px に広がり、商品の表が横スクロールなしで
 * 「販売」列まで見えるようになるのが目的です。
 * 帯の畳み（35-1）と同じ 1380px の線に統一します。
 *
 * <p><b>2〜4 は .theme-desk の中だけで書くこと。</b>
 * .badge も .tab もお客さま側（スマホ）と共用の部品です。素のクラスを角ばらせると、
 * メニューのカテゴリのチップまで四角くなります。
 * CLAUDE.md の「管理画面だけ変えたいときは .theme-desk 配下に書く」がここの根拠です。
 */
@DisplayName("Render トンマナの残り（畳み・タブ・探す欄・札）")
class RenderToneRolloutTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    private String block(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(0);
        return css.substring(at, css.indexOf("}", at));
    }

    @Test
    @DisplayName("★ サイドバーは 1380px 以下で全画面畳む（盤面だけの :has をやめる）")
    void sidebarCollapsesOnEveryScreen() throws Exception {
        String css = css();

        int at = css.indexOf("@media (max-width: 1380px)");
        assertThat(at).as("1380px の帯が無い").isGreaterThan(0);
        String band = css.substring(at, css.indexOf("\n}", at));

        // 素の .staff-shell で畳む（画面を選ばない）
        assertThat(band).contains(".staff-shell {");
        assertThat(band).contains("--sb-w: 64px;");
        assertThat(band).contains("--sb-text: none;");

        // 盤面だけに絞っていた :has は廃止。コメントを落としてから見る
        assertThat(band.replaceAll("(?s)/\\*.*?\\*/", ""))
                .as("画面ごとの出し分けはやめた")
                .doesNotContain(":has(.kitchenboard)")
                .doesNotContain(":has(.hallboard)");

        // 960px の帯は不要になった（1380 が飲み込む）
        assertThat(css.replaceAll("(?s)/\\*.*?\\*/", ""))
                .as("960px の畳みは 1380px に吸収された")
                .doesNotContain("@media (max-width: 960px) {\n  .staff-shell {\n    --sb-w: 64px;");
    }

    @Test
    @DisplayName("★ タブの角は 0（箱の形・136×56 は現行のまま）")
    void tabsAreSquare() throws Exception {
        String rule = block(css(), ".theme-desk .tab {");
        assertThat(rule).contains("border-radius: 0;");
        // 形そのものは変えない。07 のモックも箱のままで角だけ落とした
        assertThat(rule).contains("width: 136px;");
        assertThat(rule).contains("height: 56px;");
    }

    @Test
    @DisplayName("★ 探す欄の角は 0・枠は #e3e3e3（表の罫とそろえる）")
    void searchBoxIsSquare() throws Exception {
        String rule = block(css(), ".theme-desk .searchbox {");
        assertThat(rule).contains("border-radius: 0;");
        assertThat(rule).contains("border-color: #e3e3e3;");
    }

    @Test
    @DisplayName("★ 札の角は 0。お客さま側のチップは丸いまま")
    void badgesAreSquareOnlyOnTheDeskSide() throws Exception {
        String css = css();
        assertThat(css).contains(".theme-desk .badge { border-radius: 0; }");

        // 素の .badge は --r-full のまま（お客さま側のメニューで使っている）
        assertThat(block(css, ".badge {")).contains("border-radius: var(--r-full);");
    }
}
