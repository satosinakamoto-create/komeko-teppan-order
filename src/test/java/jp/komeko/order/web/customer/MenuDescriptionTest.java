package jp.komeko.order.web.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品名の下に説明を出す（設計 店04 {@code 410:4275} / 2026-09-06）。
 *
 * <p>Spring を起動しないので速い（CLAUDE.md のテスト方針）。
 *
 * <p><b>何を守っているか</b><br>
 * 説明は {@code MenuItem.description} に前からありましたが、
 * 画面に出ていたのは飲み物の行だけでした。写真カードとサムネイルは
 * 品名と価格しか出していないので、<b>店主が入れた説明文が
 * どこにも出ない品</b>ができていました。
 *
 * <p>もうひとつ、2 列に並べたときの<b>価格の高さ</b>を守っています。
 * 説明が 1 行か 2 行かで下端が動くので、放っておくと
 * 隣り合う 2 枚で金額の位置がずれます。価格は隣と見比べるものなので、
 * ずれていると「どちらが高いか」を目で追えません。
 */
@DisplayName("メニューの説明文")
class MenuDescriptionTest {

    private static final Path MENU_HTML =
            Path.of("src/main/resources/templates/customer/menu.html");
    private static final Path APP_CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String rule(String selector) throws Exception {
        String css = Files.readString(APP_CSS);
        int at = css.indexOf(selector);
        assertThat(at).as(selector + " が無い").isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at));
    }

    @Test
    @DisplayName("★ 写真カードは 品名 → 説明 → 価格 を出す")
    void photoCardShowsTheDescription() throws Exception {
        String html = Files.readString(MENU_HTML);
        int at = html.indexOf("class=\"photo-card__bar\"");
        assertThat(at).as("写真カードの帯が見つからない").isGreaterThan(-1);
        String block = html.substring(at, html.indexOf("</div>", html.indexOf("photo-card__price", at)));

        assertThat(block).as("説明を出していない").contains("photo-card__note");
        assertThat(block.indexOf("photo-card__name"))
                .as("説明が品名より先にある")
                .isLessThan(block.indexOf("photo-card__note"));

        // 説明の無い品では行ごと出さない。空の行を置くと、21px ぶんの
        // 見えない余白が入って、説明のある品とカードの高さが違う理由が分からなくなる
        assertThat(block).as("説明が無い品でも空の行が出る")
                .contains("th:if=\"${hero.description}\"");
    }

    @Test
    @DisplayName("★ 品名と説明は 1 列にまとめる（価格は右に 1 つ）")
    void nameAndDescriptionShareOneColumn() throws Exception {
        String html = Files.readString(MENU_HTML);
        assertThat(html).as("品名と説明を包む列が無い").contains("class=\"photo-card__text\"");

        // 縦に積むのは包む箱の仕事。品名そのものに flex を持たせると、
        // 列の中で品名が縦に伸びて説明との間隔が変わる
        assertThat(rule(".photo-card__text {"))
                .contains("flex-direction: column;")
                .contains("gap: 4px;");
    }

    @Test
    @DisplayName("★ 帯は上端そろえ（baseline だと価格が説明に引っぱられる）")
    void theBarAlignsToTheTop() throws Exception {
        // baseline のままだと、価格は「列の最初の行」ではなく
        // 列そのものの基準に合わせに行くので、説明が付いた分だけ下がる。
        //
        // 上端そろえにしても、説明が無いときの帯の高さは 66 のまま変わらない
        // （価格の行送り 42 のほうが品名の 28 より高く、そちらが帯を決めるため）。
        // 説明が付くと 12 + 53 + 12 = 77 になる（設計どおり）。
        assertThat(rule(".photo-card__bar {")).contains("align-items: flex-start;");
    }

    @Test
    @DisplayName("★ 説明の行送りは 21（品名 28 ＋ 間 4 ＋ 21 で設計の 53）")
    void theDescriptionLineHeightAddsUpToTheDesign() throws Exception {
        String rule = rule(".photo-card__note {");

        // 13 × 1.6 = 20.8。切り上げて 21 にしないと、
        // 積み上げが 52.8 になって設計の 53 と端数でずれる
        assertThat(rule).contains("font-size: 13px;").contains("line-height: 21px;");

        // 1 行で切る。説明は 300 字まで入るので、折り返しを許すと
        // カードの高さが品によって変わり、下のサムネイルの並びが上下にずれる
        assertThat(rule).as("説明が折り返してカードが伸びる").contains("text-overflow: ellipsis;");
    }

    @Test
    @DisplayName("★ サムネイルも 品名 → 説明 → 価格 の順")
    void thumbnailShowsTheDescriptionBetweenNameAndPrice() throws Exception {
        String html = Files.readString(MENU_HTML);
        int at = html.indexOf("class=\"thumb__img\"");
        assertThat(at).as("サムネイルが見つからない").isGreaterThan(-1);
        String block = html.substring(at, html.indexOf("</a>", at));

        assertThat(block).as("説明を出していない").contains("thumb__note");
        // 価格の下に置くと、金額を見たあとで説明を読む順になる
        assertThat(block.indexOf("thumb__name"))
                .isLessThan(block.indexOf("thumb__note"));
        assertThat(block.indexOf("thumb__note"))
                .isLessThan(block.indexOf("thumb__price"));
    }

    @Test
    @DisplayName("★ 2 列の価格が下端でそろう（説明の行数が違っても）")
    void pricesLineUpAcrossTheRow() throws Exception {
        // 3 つそろって初めて効く。1 つでも欠けると、
        // 実測で最大 40px（説明 2 行ぶん）ずれる
        assertThat(rule(".thumb-row {"))
                .as("行の高さいっぱいに伸びない").contains("align-items: stretch;");
        assertThat(rule(".thumb {"))
                .as("札が行の高さまで伸びない").contains("height: 100%;");
        assertThat(rule(".thumb__price {"))
                .as("余った高さが価格の上に寄らない").contains("margin-top: auto;");

        // stretch にした代償。これが無いと写真が行の高さに合わせて縦に潰れ、
        // aspect-ratio で決めた形が崩れる
        assertThat(rule(".thumb__img {"))
                .as("写真が伸び縮みして形が崩れる").contains("flex: none;");
    }
}
