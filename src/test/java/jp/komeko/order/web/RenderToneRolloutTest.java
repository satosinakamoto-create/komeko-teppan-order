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

    /**
     * ★ タブの角は上だけ 8px（箱の形・136×56 は現行のまま）。
     *
     * <p>2026-09-13 は 0 でした。理由は「表の見出しも札も角 0 にしたので、
     * ここだけ丸いと浮く」。<b>その前提が 2026-09-20 に変わりました</b>——
     * 札もボタンも入力も 8px にそろえたので、いまは<b>タブだけ角 0 だと浮きます</b>。
     *
     * <p>下は表とつながるので、丸めるのは上の 2 隅だけです。
     */
    @Test
    @DisplayName("★ タブの角は上だけ 8px（箱の形・136×56 は現行のまま）")
    void tabsAreSquare() throws Exception {
        String rule = block(css(), ".theme-desk .tab {");
        assertThat(rule).as("タブの上の角が 8px でない").contains("border-radius: 8px 8px 0 0;");
        // 形そのものは変えない。07 のモックも箱のままで角だけ変えた
        assertThat(rule).contains("width: 136px;");
        assertThat(rule).contains("height: 56px;");
    }

    /**
     * ★ タブの間は 8px（設計 ト10：136×56 が 5 枚で合計 712px）。
     *
     * <p>実装は 0 で、5 枚が隙間なく並んでいました。
     */
    @Test
    @DisplayName("★ タブの間は 8px")
    void tabsHaveAnEightPxGap() throws Exception {
        assertThat(css())
                .as("タブの間が 8px でない（設計は 136×5 ＋ 8×4 ＝ 712px）")
                .contains(".theme-desk .tabtable__tabs { display: flex; gap: 8px;");
    }

    @Test
    @DisplayName("★ 探す欄の角は 0・枠は #e3e3e3（表の罫とそろえる）")
    void searchBoxIsSquare() throws Exception {
        String rule = block(css(), ".theme-desk .searchbox {");
        assertThat(rule).contains("border-radius: 0;");
        assertThat(rule).contains("border-color: #e3e3e3;");
    }

    /**
     * ★ 札の角は 8px。お客さま側のチップは丸いまま。
     *
     * <p>角丸は 3 度変わっています。経緯を残しておきます。
     * <ol>
     *   <li><b>2026-09-13：0</b>。Render のトンマナへ寄せて角を落とした</li>
     *   <li><b>2026-09-20 昼：4px</b>。店主の「角丸が無くて統一感ないので
     *       4px のランド付けて」</li>
     *   <li><b>2026-09-20 夕：8px</b>。ボタンを何 px にするかを調べたとき、
     *       主要ライブラリが 4〜8px（Bootstrap・Tailwind・Primer・Ant はどれも 6px）
     *       だと分かり、<b>ボタンと札を同じ 8px にそろえる</b>と決めた。
     *       カテゴリ一覧では「表示中」の札と「編集する」のボタンが同じ行に並ぶので、
     *       ここが 8 と 4 に割れていると、そろえたつもりで逆に目立つ</li>
     * </ol>
     *
     * <p>お客さま側（和モダン）は丸のままです。素の {@code .badge} を触ると
     * 注文画面のチップまで変わるので、{@code .theme-desk} の中だけで直します。
     */
    @Test
    @DisplayName("★ 札の角は 8px。お客さま側のチップは丸いまま")
    void badgesAreRoundedOnlyOnTheDeskSide() throws Exception {
        String css = css();
        assertThat(css)
                .as("店側の札が 8px になっていない（2026-09-20 夕の決定）")
                .contains(".theme-desk .badge { border-radius: 8px; }");

        // 素の .badge は --r-full のまま（お客さま側のメニューで使っている）
        assertThat(block(css, ".badge {")).contains("border-radius: var(--r-full);");
    }

    /**
     * ★ ボタンの角は 8px。共有トークン {@code --r-sm} を使わないこと。
     *
     * <p><b>ここが今回いちばん分かりにくかった所です。</b>
     * {@code .btn} はずっと {@code border-radius: var(--r-sm)} と書かれていましたが、
     * {@code --r-sm} は層ごとに値が違います。
     * <pre>
     *   :root        --r-sm: 6px
     *   .theme-snow  --r-sm: 12px   ← お客さま側のボタンはこれ
     *   .theme-desk  --r-sm:  4px   ← 店側のボタンはこれ
     * </pre>
     * つまり「同じ .btn」がお客さま側 12px・店側 4px に割れていました。
     * CSS を読むだけでは同じ 1 行にしか見えません。実測して初めて分かります。
     *
     * <p>{@code --r-sm} は入力・カード・探す欄など 11 か所が見ているので、
     * トークンのほうを 8px にするとボタン以外まで巻き込みます。
     * <b>だから .btn に直値で書きます。</b>
     */
    @Test
    @DisplayName("★ ボタンの角は 8px（--r-sm を経由しない）")
    void buttonsAreEightPxEverywhere() throws Exception {
        // ★ 行頭から探すこと。".btn {" だけだと ".cart-bar .btn {" のような
        //   別の規則に先に当たり、基底の .btn を読めません（一度踏みました）。
        String rule = block(css(), "\n.btn {");

        assertThat(rule).as("ボタンが 8px になっていない").contains("border-radius: 8px;");
        assertThat(rule)
                .as("★ 共有トークンを経由している。--r-sm は層ごとに 6/12/4px と変わるので、"
                        + "同じボタンがお客さま側と店側で別の角丸になる")
                .doesNotContain("border-radius: var(--r-sm);");
    }

    /**
     * ★ 札の高さは 26px にそろえる。
     *
     * <p>設計の部品「店/札」(1065:13127) は 26px 固定です。
     * 実装は 2026-09-20 まで <b>21.4 / 25.6 / 28.2 / 29.6px の 4 種類</b>に割れていて、
     * 同じ表の同じ行の中で札の上下がそろっていませんでした。
     * 4 ＋ 16 ＋ 4 ＋ 枠 2 ＝ 26。
     */
    @Test
    @DisplayName("★ 店側の札は 13px / 行送り 16px / 上下 4px（＝26px）")
    void deskBadgesAreTwentySixTall() throws Exception {
        String rule = block(css(), ".theme-desk .badge {\n  font-size:");
        assertThat(rule).as("札の寸法をまとめた指定が無い").isNotEmpty();
        assertThat(rule).as("字が 13px でない").contains("font-size: 13px;");
        assertThat(rule).as("行送りが 16px でない").contains("line-height: 16px;");
        assertThat(rule).as("上下の余白が 4px でない").contains("padding: 4px 8px;");
    }

    /**
     * ★ 押せない札に緑を使わない（2026-09-20、店主の決定）。
     *
     * <p>押せるものにだけ緑を使う、という線引きです。緑が「押せる」の合図なのに
     * 押せない札まで緑だと、どれが押せるのか作った本人にも分からなくなります。
     * <b>赤（品切れ・注意）は残します</b>——禁じられたのは緑だけです。
     */
    @Test
    @DisplayName("★ 押せない札は灰色（#efefef / #444444）")
    void nonPressableBadgesAreNotGreen() throws Exception {
        String rule = block(css(), ".theme-desk .badge--gf:not(.badge--act),");
        assertThat(rule).as("押せない札を灰色にする指定が無い").isNotEmpty();
        assertThat(rule).contains("background: #efefef;").contains("color: #444444;");

        // ★ --ok 系（.badge--ok）を忘れない。見た目が同じで変種だけ別
        assertThat(rule)
                .as("★ .badge--ok が抜けている。卓の「使用中」だけ緑のまま取り残される")
                .contains(".badge--ok:not(.badge--act)");
    }
}
