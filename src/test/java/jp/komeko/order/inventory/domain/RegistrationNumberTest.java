package jp.komeko.order.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegistrationNumber} のテスト。
 *
 * <p><b>このテストが守っているもの＝仕入税額控除の証憑</b><br>
 * 登録番号は「この仕入れの消費税を引いてよいか」を決める鍵です。
 * 読み違えたまま通してしまうと、あとで税務調査に出たときに
 * 引けないはずの税を引いていたことになります。
 *
 * <p>とくに大事なのは<b>検算に落ちても弾かない</b>という判断です。
 * 個人事業者の登録番号には法人番号の検算式が当てはまらないので、
 * 「不合格＝偽物」と決めつけると、正しい番号を拒否してしまいます。
 */
@DisplayName("インボイス登録番号（T + 13桁）")
class RegistrationNumberTest {

    @Nested
    @DisplayName("整形（レシートの印字はきれいとは限らない）")
    class Normalize {

        @ParameterizedTest
        @DisplayName("区切り文字や全角が混ざっていても同じ形にそろう")
        @ValueSource(strings = {
                "T7000012050002",
                "T-7000-0120-5000-2",
                "T 7000 0120 5000 2",
                "Ｔ７００００１２０５０００２",
                "登録番号 T7000012050002"
        })
        void normalizesVariousFormats(String raw) {
            assertThat(RegistrationNumber.normalize(raw)).isEqualTo("T7000012050002");
        }

        @Test
        @DisplayName("先頭の T がかすれて読めなくても、13桁そろっていれば組み立てる")
        void tolerable_missing_t() {
            // 感熱紙の T は消えやすい。ここで弾くと実運用にならない。
            assertThat(RegistrationNumber.normalize("7000012050002")).isEqualTo("T7000012050002");
        }

        @Test
        @DisplayName("桁が足りない・多い場合は null（読み違いとして人に返す）")
        void rejects_wrong_length() {
            assertThat(RegistrationNumber.normalize("T70000120500")).isNull();      // 12桁
            assertThat(RegistrationNumber.normalize("T70000120500023")).isNull();   // 14桁
        }

        @Test
        @DisplayName("null や空文字は null のまま")
        void handles_null() {
            assertThat(RegistrationNumber.normalize(null)).isNull();
            assertThat(RegistrationNumber.normalize("")).isNull();
        }
    }

    @Nested
    @DisplayName("検査用数字（法人番号のときだけ検算できる）")
    class CheckDigit {

        @Test
        @DisplayName("国税庁の法人番号 7000012050002 は検算に通る")
        void nta_own_number_passes() {
            // 国税庁が公開している計算式:
            //   検査用数字 = 9 −(Σ Pn × Qn を 9 で割った余り)
            //   下1桁目から P1..P12、n が奇数なら 1 倍・偶数なら 2 倍
            // 残り12桁 000012050002 で Σ = 11、11 mod 9 = 2、9 − 2 = 7 → 先頭の 7 と一致
            assertThat(RegistrationNumber.matchesCorporateCheckDigit("T7000012050002")).isTrue();
        }

        @Test
        @DisplayName("1桁でも読み違えると検算で落ちる（OCR の誤読を拾える）")
        void detects_single_digit_error() {
            assertThat(RegistrationNumber.matchesCorporateCheckDigit("T7000012050003")).isFalse();
        }

        @Test
        @DisplayName("形が違うものは検算以前に false")
        void rejects_bad_format() {
            assertThat(RegistrationNumber.matchesCorporateCheckDigit("1234567890123")).isFalse();
            assertThat(RegistrationNumber.matchesCorporateCheckDigit(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("表示用の整形")
    class Display {

        @Test
        @DisplayName("4桁ずつ区切って読みやすくする")
        void formats_with_hyphens() {
            assertThat(RegistrationNumber.forDisplay("T7000012050002"))
                    .isEqualTo("T7000-0120-5000-2");
        }
    }
}
