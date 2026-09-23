package jp.komeko.order.inventory.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 税率・控除率の改定フォームを設計 ト05f（841:10213）の 1 行に合わせる（2026-09-17）。
 *
 * <p>2026-09-14 の差分チェックで「要修正（実装）」に挙がっていた
 * <b>「改定フォームを 1 行横並び＋右端ボタンに」</b>の 1 件です。
 *
 * <p>実装は 3 列のグリッドの下にメモ欄・説明文・ボタンが縦に積まれていました。
 * 改定は<b>年に何度もやる作業ではない</b>ぶん、開いたときに
 * 「何を入れる画面か」が一目で分かるほうがよく、縦に伸びていると
 * 入力欄を探すところから始まります。
 *
 * <p>説明文（「同じ区分でいま有効な行に自動で終了日が入ります」）は残します。
 * ここを黙ると、過去の行が勝手に閉じられる理由が誰にも分かりません。
 * 行の中には入れず、行の下に置きます。
 *
 * <p>新しい CSS は足していません（{@code .row} / {@code .row__grow} / {@code .mb-0}）。
 */
@DisplayName("税率・控除率の改定フォームは設計 ト05f どおり 1 行")
class TaxRateFormMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/tax-rates.html");

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    /** ★ 2 つの改定フォーム（税率・控除率）が、どちらも 1 行になっていること。 */
    @Test
    @DisplayName("★ 改定フォームは 2 つとも 1 行（.row）に組まれている")
    void bothRevisionFormsAreASingleRow() throws Exception {
        String html = body();

        Matcher m = Pattern.compile("class=\"row row--wrap\"").matcher(html);
        int rows = 0;
        while (m.find()) rows++;

        assertThat(rows)
                .as("1 行に組まれた改定フォームが %d 個（税率・控除率の 2 つのはず）", rows)
                .isEqualTo(2);

        assertThat(html).as("3 列グリッドの元の形が残っている")
                .doesNotContain("grid grid--3");
    }

    /**
     * ★ 登録ボタンは行の右端、つまり入力と同じ行の中にある。
     *
     * <p>行の開始より後ろにボタンがあることを見ます。行の外（下）にぶら下がっていれば、
     * 直前の行の開始より後ろではあるものの、間に {@code </div>} で行が閉じられます。
     * そこで「行が閉じる前にボタンが来る」ことを確かめます。
     */
    @Test
    @DisplayName("★ 登録ボタンが行の中にある（下にぶら下がっていない）")
    void theSubmitButtonIsInsideTheRow() throws Exception {
        String html = body();

        int from = 0;
        for (int i = 0; i < 2; i++) {
            int row = html.indexOf("class=\"row row--wrap\"", from);
            assertThat(row).as("%d 個目の行が無い", i + 1).isGreaterThan(0);

            int button = html.indexOf("この内容で登録する</button>", row);
            assertThat(button).as("%d 個目の行のあとにボタンが無い", i + 1).isGreaterThan(row);

            // 行の中身に入力欄とボタンが両方あること
            String between = html.substring(row, button);
            assertThat(between).as("%d 個目の行に入力欄が無い", i + 1).contains("class=\"input\"");

            from = button;
        }
    }

    /**
     * ★ 「古い行に自動で終了日が入る」の説明は残す。
     *
     * <p>過去の仕入れはその行を根拠に計算されているので、古い行は消せません。
     * 消さずに閉じる、という仕組みを画面で言っておかないと、
     * 「登録したら前の行が変わった」と見えます。
     */
    @Test
    @DisplayName("★ 終了日が自動で入る説明は画面に残っている")
    void theAutoCloseExplanationSurvives() throws Exception {
        assertThat(body())
                .as("古い行に終了日が入る説明が画面から消えている")
                .contains("自動で終了日");
    }
}
