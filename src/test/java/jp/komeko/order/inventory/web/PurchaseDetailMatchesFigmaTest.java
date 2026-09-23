package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.domain.Purchase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仕入れの詳細を設計 ト05e（840:9735）に合わせる（2026-09-17、店主の指示）。
 *
 * <p><b>レイアウト。</b>設計は 2 カラムです。
 *
 * <pre>
 *   左（広い）   証憑としての状態 ／ 明細
 *   右（狭い）   レシート画像 ＋ 7 年保存の注記 ＋ 原寸で開く
 * </pre>
 *
 * <p>実装は 4 枚のカードを縦に積んでいました。レシート画像がいちばん下にあるので、
 * <b>数字と画像を見比べるのに画面をスクロールで往復する</b>ことになります。
 * この画面の仕事はまさに「紙と見比べる」ことなので、横に並べるほうが目的に合います。
 *
 * <p><b>足りなかった部品が 3 つ。</b>
 * <ul>
 *   <li><b>入力期限の行</b> …… 設計は期限内でも「受領から 0 日（期限内）」を常に出します。
 *       実装は期限を過ぎたときの赤い警告だけで、<b>期限内のときは何も言いません</b>。
 *       「まだ大丈夫」が見えないと、毎回上の警告の有無で判断することになります</li>
 *   <li><b>原寸で開く</b> …… カードの中の画像は縮んでいます。レシートの小さい字を
 *       読むには原寸が要ります</li>
 *   <li><b>7 年間保存されます の注記</b> …… なぜこの画像を消せないのかの説明。
 *       電子帳簿保存法の要件です</li>
 * </ul>
 */
@DisplayName("仕入れの詳細は設計 ト05e どおり")
class PurchaseDetailMatchesFigmaTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/inventory/purchase-detail.html");

    private String body() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n")
                .replaceAll("(?s)<!--.*?-->", "");
    }

    // ------------------------------------------------------------------
    // レイアウト
    // ------------------------------------------------------------------

    /**
     * ★ 2 カラム。左に 状態＋明細、右に レシート画像。
     *
     * <p>並び順も見ます。右の列が先に来ると、狭い画面で折り返したときに
     * 画像が数字より上に出てしまいます。
     */
    @Test
    @DisplayName("★ 2 カラム（左＝状態と明細／右＝レシート画像）")
    void theScreenIsTwoColumns() throws Exception {
        String html = body();

        int wrap = html.indexOf("class=\"detail2\"");
        assertThat(wrap).as("2 カラムの箱（.detail2）が無い").isGreaterThan(0);

        int left = html.indexOf("detail2__main", wrap);
        int right = html.indexOf("detail2__side", wrap);
        assertThat(left).as("左の列が無い").isGreaterThan(0);
        assertThat(right).as("右の列が無い").isGreaterThan(0);
        assertThat(left).as("右の列が左より先にある（狭い画面で画像が上に出る）").isLessThan(right);

        // 状態と明細は左、画像は右
        int state = html.indexOf("証憑としての状態");
        int lines = html.indexOf("明細</div>");
        int image = html.indexOf("レシート画像");
        assertThat(state).as("状態のカードが左に無い").isBetween(left, right);
        assertThat(lines).as("明細のカードが左に無い").isBetween(left, right);
        assertThat(image).as("レシート画像が右に無い").isGreaterThan(right);
    }

    // ------------------------------------------------------------------
    // 足りなかった 3 つ
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 入力期限の行が常に出る（期限内でも）")
    void theDeadlineRowIsAlwaysThere() throws Exception {
        String html = body();

        // ★ 見出しセルで探すこと。上の警告文にも「入力期限」という語があるので、
        //   ただの indexOf だとそちらを拾ってしまう。
        int at = html.indexOf("<th>入力期限</th>");
        assertThat(at).as("状態の表に入力期限の行が無い").isGreaterThan(0);

        // 行そのものが th:if で消えないこと（中の文言の出し分けは可）
        String row = html.substring(html.lastIndexOf("<tr", at), at);
        assertThat(row)
                .as("入力期限の行が条件付きで、期限内のときに消える")
                .doesNotContain("th:if");

        // 期限内・期限切れの両方の文言があること
        assertThat(html).as("期限内のときの文言が無い").contains("（期限内）");
        assertThat(html).as("受領からの日数を出していない").contains("daysFromReceipt()");
    }

    @Test
    @DisplayName("★ 原寸で開くボタンがある")
    void thereIsAFullSizeLink() throws Exception {
        assertThat(body()).as("原寸で開く が無い").contains("原寸で開く");
    }

    @Test
    @DisplayName("★ 7 年間保存される理由が書いてある")
    void theSevenYearNoteIsThere() throws Exception {
        String html = body();
        assertThat(html).as("7 年間保存の注記が無い").contains("7 年間保存");
        assertThat(html).as("なぜ消せないのかの根拠（電子帳簿保存法）が無い")
                .contains("電子帳簿保存法");
    }

    // ------------------------------------------------------------------
    // 期限の日数（画面に出す値）
    // ------------------------------------------------------------------

    /**
     * ★「受領から N 日」の N を出す。
     *
     * <p>受領日とシステムに保存した日時の差です。利用者の端末の時計ではなく
     * {@code storedAt}（サーバが打った時刻）を使うのは、あとから手入力で
     * ごまかせないようにするためで、既存の設計どおりです。
     */
    @Test
    @DisplayName("★ 受領からの日数を数えられる")
    void itCountsTheDaysFromReceipt() {
        Purchase p = new Purchase(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), "業務スーパー",
                4755, null, LocalDateTime.of(2026, 9, 13, 19, 4));

        assertThat(p.daysFromReceipt()).as("受領から 3 日のはず").isEqualTo(3);
    }

    /** 同じ日に登録したら 0 日。設計の「受領から 0 日（期限内）」がこれ。 */
    @Test
    @DisplayName("★ 同じ日に登録したら 0 日")
    void sameDayIsZero() {
        Purchase p = new Purchase(
                LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 13), "業務スーパー",
                4755, null, LocalDateTime.of(2026, 9, 13, 19, 4));

        assertThat(p.daysFromReceipt()).isEqualTo(0);
    }
}
