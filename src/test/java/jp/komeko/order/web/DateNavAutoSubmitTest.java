package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日付・月は「選んだら表示」にする（2026-09-18、店主の判断）。
 *
 * <p><b>もとの形。</b>日付・月の帯に「この日を表示 / この月を表示」という送信ボタンが
 * 並んでいました。{@code <input type="date">} は値を入れるだけの部品で、
 * カレンダーで選んでもサーバーには何も送られないためです。
 *
 * <p><b>店主の指摘。</b>「選択されてんだから、そこは自動で表示しろよ。
 * キーボードで打てなくすりゃいいじゃん、カレンダーがあるんだから」
 *
 * <p>これは正しい割り切りです。自動送信をためらっていた理由は
 * <b>キーボードで打つ途中に飛ばされる</b>ことでした。
 *
 * <pre>
 *   2026/09/18 と打とうとすると…
 *     2 0 2 6 0 9 1 8
 *               ↑ ここで「2026/09/01」が完成したとみなされ、一度送信されてしまう
 * </pre>
 *
 * <p>打てなくすれば、この問題は起きません。<b>カレンダーから選ぶ道が残っている</b>ので、
 * 打てないことによる不便もありません。
 *
 * <p><b>JavaScript を使います。</b>スタッフ側は素の HTML で動かす決まりですが、
 * 送信は HTML だけではどうやっても起こせません。そのかわり
 * <b>JavaScript が動かなくても壊れない形</b>にしてあります。
 *
 * <pre>
 *   JS が動く   … ボタンは隠れていて、選んだ瞬間に表示が切り替わる
 *   JS が動かない … &lt;noscript&gt; がボタンを出すので、押せば今までどおり
 * </pre>
 *
 * <p>ボタンを HTML から消してしまうと後者が成立しません。<b>消さずに隠す</b>のが要点です。
 */
@DisplayName("日付・月は選んだら表示する")
class DateNavAutoSubmitTest {

    private static final Path JS =
            Path.of("src/main/resources/static/js/datenav.js");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/app.css");

    /** 日付・月の帯を持つ 3 画面（税理士は 6 画面ぶんをレイアウトが持っている）。 */
    private static final List<Path> PAGES = List.of(
            Path.of("src/main/resources/templates/admin/orders.html"),
            Path.of("src/main/resources/templates/inventory/purchases.html"),
            Path.of("src/main/resources/templates/layout/accountant.html"));

    /** JavaScript を読み込む 2 つのレイアウト。 */
    private static final List<Path> LAYOUTS = List.of(
            Path.of("src/main/resources/templates/layout/staff.html"),
            Path.of("src/main/resources/templates/layout/accountant.html"));

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** テンプレートのコメント（{@code <!--/* ... *​/-->}）を落とす。文言の検査に混ざらないように。 */
    private String body(Path p) throws Exception {
        return read(p).replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 選んだ瞬間に送信する。
     *
     * <p>これが無いと、カレンダーで選んでも画面は変わりません。
     */
    @Test
    @DisplayName("★ 選んだら、その場で送信する")
    void itSubmitsWhenTheValueChanges() throws Exception {
        assertThat(JS).as("datenav.js が無い").exists();
        String js = read(JS);

        assertThat(js).as("change を見ていない。選んでも何も起きない")
                .contains("'change'");
        assertThat(js).as("送信していない").contains("requestSubmit");
    }

    /**
     * ★ キーボードでは打たせない。
     *
     * <p>打てると、打ち終わる前に「完成した日付」とみなされて画面が飛びます。
     * カレンダーから選ぶ道は残るので、打てなくても操作はできます。
     */
    @Test
    @DisplayName("★ 文字キーは受け付けない（打つ途中で飛ばされないため）")
    void itRefusesTypedCharacters() throws Exception {
        String js = read(JS);

        assertThat(js).as("keydown を見ていない。日付を打てたままになる")
                .contains("'keydown'");
        assertThat(js).as("キー入力を止めていない").contains("preventDefault");
        assertThat(js)
                .as("文字キーだけを止める判定（key.length === 1）が無い。"
                        + "全部止めると Tab も Esc も矢印も効かなくなり、"
                        + "キーボードだけで操作している人がカレンダーを開けなくなる")
                .contains("length === 1");
    }

    /**
     * ★ ボタンは消さずに隠す。
     *
     * <p>JavaScript が動かない環境では、ボタンが唯一の送信手段になります。
     * HTML から消すと、その環境で月を変えられなくなります。
     */
    @Test
    @DisplayName("★ 送信ボタンは HTML に残っている（JS 無しの逃げ道）")
    void theSubmitButtonStaysInTheHtml() throws Exception {
        for (Path p : PAGES) {
            String html = body(p);
            assertThat(html)
                    .as(p + " から送信ボタンが消えている。"
                            + "JavaScript が動かない環境で日付・月を変えられなくなる")
                    .contains("datenav__go");
            assertThat(html).as(p + " の送信ボタンが submit ではない")
                    .contains("type=\"submit\"");
        }
    }

    /** ★ 既定では隠れていること。JS が動く環境では押す必要がない。 */
    @Test
    @DisplayName("★ 送信ボタンは既定で隠れている")
    void theSubmitButtonIsHiddenByDefault() throws Exception {
        assertThat(read(CSS))
                .as("app.css に .datenav__go を隠す指定が無い。"
                        + "自動送信になったのにボタンが残って二度手間に見える")
                .containsPattern("\\.datenav__go\\s*\\{[^}]*display:\\s*none");
    }

    /**
     * ★ JavaScript が動かないときだけボタンを出す。
     *
     * <p>{@code <noscript>} の中の {@code <style>} は、JavaScript が無効なときだけ効きます。
     * これが無いと、隠したボタンが二度と出てこない＝月を変えられない画面になります。
     */
    @Test
    @DisplayName("★ JS が動かないときは noscript がボタンを出す")
    void noscriptBringsTheButtonBack() throws Exception {
        for (Path p : LAYOUTS) {
            String html = read(p);
            int at = html.indexOf("<noscript>");
            assertThat(at)
                    .as(p + " に noscript が無い。"
                            + "JavaScript が無効な環境では送信ボタンが隠れたままになる")
                    .isGreaterThan(0);

            String block = html.substring(at, html.indexOf("</noscript>", at));
            assertThat(block).as(p + " の noscript がボタンを出していない")
                    .contains(".datenav__go");
            assertThat(block).as(p + " の noscript が display: none を打ち消していない")
                    .contains("display: inline-flex");
        }
    }

    /** ★ 2 つのレイアウトが datenav.js を読み込んでいること。片方だけだと税理士か店舗管理で効かない。 */
    @Test
    @DisplayName("★ 店舗管理と帳簿の両方が datenav.js を読み込む")
    void bothLayoutsLoadTheScript() throws Exception {
        for (Path p : LAYOUTS) {
            assertThat(read(p))
                    .as(p + " が datenav.js を読み込んでいない。"
                            + "この画面だけ自動送信にならず、隠れたボタンも押せない")
                    .contains("/js/datenav.js");
        }
    }
}
