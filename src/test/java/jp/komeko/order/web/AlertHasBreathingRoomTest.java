package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * お知らせの帯に上下の余白を持たせる（2026-09-17、店主の指摘）。
 *
 * <p><b>何が起きていたか。</b>{@code .alert} は内側の余白（padding）だけ持っていて、
 * <b>外側の余白（margin）を 1 つも持っていませんでした</b>。そのため帯は前後の要素に
 * ぴったり張り付きます。税理士の「今月のまとめ」では、緑の帯が題と数値カードの
 * 両方に接していました。
 *
 * <p>画面側では {@code mt-4} や {@code mt-6} を足して逃げていましたが、
 * <b>71 か所あるうち余白クラスが付いているのは 21 か所だけ</b>でした。
 * 残り 50 か所は、書いた人が気づかないまま張り付いていたことになります。
 * 「使う側が毎回思い出さないと正しくならない部品」は、部品のほうが悪いので
 * 既定値を持たせます。
 *
 * <p><b>ユーティリティは今までどおり効きます。</b>{@code .mt-4} などの定義は
 * app.css の 1595 行あたり、{@code .alert} は 1073 行あたりで、
 * <b>後ろに書いてあるほうが勝つ</b>ためです。
 * 余白を消したいところは {@code mb-0} がそのまま使えます。
 */
@DisplayName("お知らせの帯は前後に余白を持つ")
class AlertHasBreathingRoomTest {

    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    private String css() throws Exception {
        return Files.readString(CSS).replace("\r\n", "\n");
    }

    /** セレクタ 1 件ぶんの中身を取り出す（空白の数は数えない）。 */
    private String rule(String css, String selector) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{")
                .matcher(css);
        assertThat(m.find()).as("%s が app.css に無い", selector).isTrue();
        return css.substring(m.start(), css.indexOf("}", m.start()));
    }

    @Test
    @DisplayName("★ .alert は上下に余白を持つ（前後の要素に張り付かない）")
    void theAlertKeepsItsDistance() throws Exception {
        String r = rule(css(), ".alert");

        assertThat(r).as("内側の余白が無い").contains("padding:");
        assertThat(r)
                .as("外側の余白が無い。画面側で mt-4 を足さないと前後に張り付く")
                .contains("margin:");
    }

    /**
     * ★ ユーティリティが後ろに定義されていること。
     *
     * <p>これが崩れると、{@code mb-0} で余白を消せなくなります。
     * 既定値を入れた以上、消す手段が効くことまで込みで守ります。
     */
    @Test
    @DisplayName("★ 余白のユーティリティは .alert より後ろに定義されている")
    void theUtilitiesStillWin() throws Exception {
        String css = css();

        int alert = css.indexOf("\n.alert {");
        int mb0 = css.indexOf("\n.mb-0 {");
        int mt4 = css.indexOf("\n.mt-4 {");

        assertThat(alert).as(".alert が無い").isGreaterThan(0);
        assertThat(mb0).as(".mb-0 が .alert より前にあり、余白を消せない").isGreaterThan(alert);
        assertThat(mt4).as(".mt-4 が .alert より前にある").isGreaterThan(alert);
    }
}
