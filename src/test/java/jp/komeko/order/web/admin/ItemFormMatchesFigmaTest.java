package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品の追加フォームを設計 ト10b（745:7822）に合わせる（2026-09-17、店主の指摘）。
 *
 * <p>店主から「昔の UI のまま」と言われて画面を見たところ、
 * <b>掲載・販売の選択が旧ティールの塗りつぶし</b>になっていました。
 *
 * <pre>
 *   設計   選択中は 白地・緑枠・緑文字
 *   実装   選択中は var(--accent) の塗りつぶし
 * </pre>
 *
 * <p>{@code --accent} は {@code :root} では黒ですが {@code .theme-desk} が
 * ティール（{@code --green-700}）に上書きします。売上の期間切り替えと同じ取り残しで、
 * <b>スタッフ側だけ旧色で残る</b>場所がまだあったことになります。
 *
 * <p>白地・緑枠・緑文字は、今日そろえた「押せるものの形」と同じです
 * （{@code .recbtn} の記録する、{@code .badge--act} の掲載中）。
 *
 * <p>あわせて 2 つ。
 * <ul>
 *   <li><b>下の重複ボタンを外しました。</b>「掲載する／下書きのまま保存」は帯の右上にあり、
 *       同じものが画面の下にもう 1 組ありました。設計にはありません。
 *       折りたたみを畳んだ状態ではフォームが 1 画面に収まるので、
 *       下まで送る必要がありません</li>
 *   <li><b>戻り口をボタンにしました。</b>素のテキストリンクで、他の画面
 *       （カテゴリ・スタッフ・仕入れ）はどれも帯の中のボタンです。
 *       同じ役の見た目が画面ごとに違うと、探し方が毎回変わります</li>
 * </ul>
 */
@DisplayName("商品の追加フォームは設計 ト10b どおり")
class ItemFormMatchesFigmaTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");
    private static final Path TPL =
            Path.of("src/main/resources/templates/admin/item-form.html");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 選択中は白地・緑枠・緑文字。
     *
     * <p>{@code --accent} を使わないことまで見ます。値が同じでも、
     * {@code --accent} はテーマごとに別の色に化けるので、
     * 「押せるものの緑」を意味しません。
     */
    @Test
    @DisplayName("★ 掲載・販売の選択中は 白地・緑枠・緑文字（塗りつぶさない）")
    void theCheckedStateIsOutlinedInTheActionGreen() throws Exception {
        String css = css();

        int at = css.indexOf(".statepick__item input:checked + span");
        assertThat(at).as("選択中の規則が app.css に無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));

        assertThat(rule).as("まだ旧色（--accent はスタッフ側でティールに化ける）")
                .doesNotContain("var(--accent)");
        assertThat(rule).as("枠が草緑でない").contains("border-color: var(--action)");
        assertThat(rule).as("文字が草緑でない").contains("color: var(--action)");
        assertThat(rule).as("面を塗りつぶしている（設計は白地）")
                .contains("background: var(--bg-elevated)");
    }

    /** ★ 同じボタンを 1 画面に 2 組置かない。 */
    @Test
    @DisplayName("★ 掲載するボタンは 1 つだけ（下に重複させない）")
    void theSubmitButtonAppearsOnce() throws Exception {
        String html = body();

        Matcher m = Pattern.compile("掲載する\\s*</button>|>掲載する<").matcher(html);
        int count = 0;
        while (m.find()) count++;

        assertThat(count)
                .as("「掲載する」が %d 個ある（帯の右上の 1 つだけのはず）", count)
                .isEqualTo(1);

        assertThat(html).as("「下書きのまま保存」が重複している")
                .containsOnlyOnce("下書きのまま保存");
    }

    /**
     * ★ 戻り口は他の画面と同じボタンの形。
     *
     * <p>下の「やめる」を外すので、ここが唯一の戻り口になります。
     * 素のテキストリンクのままだと見つけにくいままです。
     */
    @Test
    @DisplayName("★ 戻り口はボタンの形（素のリンクにしない）")
    void theWayBackLooksLikeAButton() throws Exception {
        String html = body();

        int at = html.indexOf("商品の一覧にもどる");
        assertThat(at).as("戻り口が無い").isGreaterThan(0);

        String tag = html.substring(html.lastIndexOf("<a", at), at);
        assertThat(tag).as("戻り口がボタンの形になっていない").contains("class=\"btn");
    }
}
