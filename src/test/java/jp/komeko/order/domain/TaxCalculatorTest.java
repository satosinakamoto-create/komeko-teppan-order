package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaxCalculator} のテスト。
 *
 * <p><b>このテストが守っているもの＝「お金の計算」</b><br>
 * 消費税の計算がずれると、レシートの金額が合わなくなり、
 * 最悪の場合は帳簿・確定申告にまで影響します。
 * 金額計算は「あとから気づきにくいバグ」の代表なので、
 * 仕様（税込 850 円・8% なら内税 62 円）を数字で固定しておきます。
 *
 * <p><b>なぜ Spring を起動しないのか</b><br>
 * {@code TaxCalculator} は DB もインターネットも使わない、ただの計算クラスです。
 * こういうクラスは {@code @SpringBootTest} を付けずに素の JUnit で書けます。
 * Spring の起動には数秒かかりますが、素の JUnit ならミリ秒で終わります。
 * 「速いテストほど何度も回せる＝バグが早く見つかる」ので、
 * <b>Spring を使わずに済むならとことん使わない</b>のが定石です。
 *
 * <p><b>メソッド名が英語なのに @DisplayName が日本語なのはなぜ？</b><br>
 * Java のメソッド名には日本語も使えますが、コンパイル結果のファイル名にも
 * 日本語が入って環境によって扱いが変わることがあります。
 * JUnit 5 の {@code @DisplayName} を使えば、実行結果の一覧には日本語が出るので、
 * 「コードは英語・レポートは日本語」という組み合わせが安全で読みやすくなります。
 */
@DisplayName("消費税の計算（内税・切り捨て）")
class TaxCalculatorTest {

    @Nested
    @DisplayName("代表的なケース")
    class TypicalCases {

        @Test
        @DisplayName("税込850円・税率8% なら 内税62円・税抜788円 になる")
        void includedTax_850yen_8percent() {
            // 850 × 8 ÷ 108 = 62.96... → 1円未満は切り捨てて 62 円
            int tax = TaxCalculator.includedTax(850, 8);

            assertThat(tax).isEqualTo(62);
            // 税抜は「税込 − 内税」。別々に計算せず引き算で出すことで、
            // 税込 = 税抜 + 内税 が必ず成り立つようにしている。
            assertThat(TaxCalculator.netAmount(850, 8)).isEqualTo(788);
            assertThat(TaxCalculator.netAmount(850, 8) + tax).isEqualTo(850);
        }

        @Test
        @DisplayName("金額が0円なら税額も0円")
        void includedTax_zeroAmount() {
            assertThat(TaxCalculator.includedTax(0, 8)).isZero();
            assertThat(TaxCalculator.netAmount(0, 8)).isZero();
        }

        @Test
        @DisplayName("税率が0%なら税額は0円（税抜＝税込）")
        void includedTax_zeroRate() {
            assertThat(TaxCalculator.includedTax(850, 0)).isZero();
            assertThat(TaxCalculator.netAmount(850, 0)).isEqualTo(850);
        }

        @Test
        @DisplayName("税率がマイナスでも0円を返す（不正な設定でも金額が壊れない）")
        void includedTax_negativeRate() {
            // 管理画面のバリデーションをすり抜けた値が来ても、
            // マイナスの税額を返して合計金額を壊さないことを保証する。
            assertThat(TaxCalculator.includedTax(850, -8)).isZero();
            assertThat(TaxCalculator.netAmount(850, -8)).isEqualTo(850);
        }
    }

    @Nested
    @DisplayName("切り捨ての方向（境界値）")
    class BoundaryValues {

        /**
         * <b>境界値テスト</b>：バグは「ちょうどの値」の前後に潜みます。
         * 例えば切り上げで実装してしまうと 13 円のときに 1 円ずれ、
         * 四捨五入で実装してしまうと 26 円のときに 1 円ずれます。
         *
         * <p>{@code @ParameterizedTest} は同じテストを値を変えて何度も実行する仕組みです。
         * 似たテストメソッドを 10 個並べるより、表として読めるぶん見通しが良くなります。
         */
        @ParameterizedTest(name = "税込{0}円・税率{1}% → 内税{2}円")
        @CsvSource({
                // --- 税率 8%（テイクアウトの軽減税率）: 税額 = 金額 × 8 ÷ 108 ---
                "   1,  8,   0",   // 0.074 → 0（1円に満たないので切り捨て）
                "  13,  8,   0",   // 0.962 → 0（あと少しで1円。切り上げなら1になってしまう）
                "  14,  8,   1",   // 1.037 → 1（ここで初めて1円になる）
                "  26,  8,   1",   // 1.925 → 1（四捨五入なら2になってしまう）
                "  27,  8,   2",   // 2.000 → 2（割り切れるちょうどの値）
                " 107,  8,   7",   // 7.925 → 7
                " 108,  8,   8",   // 8.000 → 8（割り切れる）
                " 850,  8,  62",   // 62.96 → 62（実際のメニュー価格）
                "2160,  8, 160",   // 160.0 → 160（ガレット880×2 ＋ コーヒー400）

                // --- 税率 10%（将来イートインを足したときのため）: 税額 = 金額 × 10 ÷ 110 ---
                "  10, 10,   0",   // 0.909 → 0
                "  11, 10,   1",   // 1.000 → 1（割り切れる）
                " 109, 10,   9",   // 9.909 → 9（四捨五入なら10になってしまう）
                " 110, 10,  10",   // 10.00 → 10
                " 999, 10,  90"    // 90.81 → 90
        })
        void includedTax_roundsDown(int amount, int rate, int expectedTax) {
            assertThat(TaxCalculator.includedTax(amount, rate)).isEqualTo(expectedTax);
        }

        @ParameterizedTest(name = "税込{0}円・税率{1}% でも 税抜＋内税＝税込 が崩れない")
        @CsvSource({"1, 8", "13, 8", "14, 8", "850, 8", "999, 10", "123456, 8"})
        void netPlusTaxAlwaysEqualsTotal(int amount, int rate) {
            // 切り捨てのぶんは「税抜側」が吸収する設計。
            // ここが崩れると、レシートの内訳と合計が 1 円合わない不具合になる。
            int tax = TaxCalculator.includedTax(amount, rate);
            int net = TaxCalculator.netAmount(amount, rate);

            assertThat(net + tax).isEqualTo(amount);
        }

        @Test
        @DisplayName("高額でも桁あふれ（オーバーフロー）しない")
        void includedTax_largeAmount() {
            // int どうしの掛け算は約21億を超えると値が壊れる（オーバーフロー）。
            // TaxCalculator は long にキャストしてから掛けているので壊れないはず。
            // 極端な金額を入れて、その保険が効いていることを確認しておく。
            assertThat(TaxCalculator.includedTax(1_000_000_000, 8)).isEqualTo(74_074_074);
        }
    }
}
