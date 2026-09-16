package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戻り口は 1 画面に 1 つ（設計 07 現行レイアウト × Render トンマナ・2026-09-17）。
 *
 * <p><b>何が起きていたか。</b>「編集・追加」の画面に、一覧へ戻るリンクが<b>2 つ</b>ありました。
 *
 * <pre>
 *   &lt;p class="mb-0"&gt;&lt;a class="btn btn--sm"&gt;← スタッフ一覧へ&lt;/a&gt;&lt;/p&gt;   ← 帯の外
 *   &lt;div class="page-head"&gt;
 *     &lt;a class="btn"&gt;← スタッフ一覧へ戻る&lt;/a&gt;                            ← 帯の中
 * </pre>
 *
 * <p>行き先は同じで、文言だけ微妙に違います。設計（ト15b・ト11b）は帯の中の 1 つだけです。
 * 帯の外の 1 つは、帯を作る前の名残でした。
 *
 * <p><b>同じ場所へ行く道を 2 つ持たない</b>のは、このプロジェクトで何度か採ってきた判断です
 * （原価表の商品名リンク、QR の印刷の入口）。押す前にどちらを押すか考えさせるだけで、
 * 得るものがありません。しかも 2 つが縦に並ぶぶん、題が下へ押し下げられていました。
 *
 * <p>ファイルを読むだけのテストなので Spring を起動しません。
 */
@DisplayName("戻り口は 1 画面に 1 つ")
class BackLinkIsSingleTest {

    /** 帯の中に戻るボタンを持つ「編集・追加」系の画面。 */
    private static final String[] SCREENS = {
            "src/main/resources/templates/admin/staff.html",
            "src/main/resources/templates/admin/categories.html",
    };

    /** コメントを落とした本文。コメントの中の「←」を数えないため。 */
    private String body(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 一覧へ戻るリンクが 2 つ並んでいない")
    void thereIsExactlyOneWayBack() throws Exception {
        for (String path : SCREENS) {
            String html = body(path);

            Matcher m = Pattern.compile("<a\\b[^>]*>\\s*←[^<]*</a>").matcher(html);
            int count = 0;
            while (m.find()) count++;

            assertThat(count)
                    .as("%s に戻るリンクが %d 本ある（1 本のはず）", path, count)
                    .isEqualTo(1);
        }
    }

    /**
     * ★ 残す 1 つは<b>帯の中</b>。
     *
     * <p>帯の外に置くと、題より上に独立した行ができて、そのぶん本文が下がります。
     * 設計では戻る → 題 → 補足 → のばす → 実行 が 1 本の帯に収まっています。
     */
    @Test
    @DisplayName("★ 残った戻り口は見出し帯の中にある")
    void theRemainingOneSitsInsideTheHeadBand() throws Exception {
        for (String path : SCREENS) {
            String html = body(path);

            int band = html.indexOf("class=\"page-head\"");
            assertThat(band).as("%s に見出し帯が無い", path).isGreaterThan(0);

            int back = html.indexOf("←");
            assertThat(back).as("%s に戻るリンクが無い", path).isGreaterThan(0);
            assertThat(back)
                    .as("%s の戻るリンクが帯の外（帯より前）にある", path)
                    .isGreaterThan(band);
        }
    }
}
