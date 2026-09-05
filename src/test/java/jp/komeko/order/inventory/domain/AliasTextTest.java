package jp.komeko.order.inventory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * レシート品名の名寄せのテスト。
 *
 * <p><b>これが守っているもの＝「1 回教えたら次から自動」が本当に効くこと</b><br>
 * 同じエリンギが、半角カナのレシートと全角のレシートで別物として扱われたら、
 * 何度でも教え直すはめになり、この仕組みは使われなくなります。
 *
 * <p>逆に<b>寄せすぎてもいけません</b>。「牛乳500」と「牛乳1000」を
 * 同じものにしてしまうと、内容量が黙って間違い、在庫が 2 倍ずれます。
 * どこまで寄せてどこから区別するか、その線をここで固定します。
 */
@DisplayName("レシート品名の名寄せ")
class AliasTextTest {

    @Nested
    @DisplayName("同じ物だと分かってほしいもの")
    class ShouldMatch {

        @Test
        @DisplayName("半角カナと全角カナ（レシートによって混在する）")
        void halfwidth_katakana() {
            assertThat(AliasText.sameItem("ｷｬﾍﾞﾂ", "キャベツ")).isTrue();
            assertThat(AliasText.sameItem("ｴﾘﾝｷﾞ", "エリンギ")).isTrue();
        }

        @Test
        @DisplayName("全角の数字と半角の数字")
        void fullwidth_digits() {
            assertThat(AliasText.sameItem("牛乳１０００", "牛乳1000")).isTrue();
        }

        @Test
        @DisplayName("軽減税率の印や記号は品物の違いではない")
        void decorations_ignored() {
            assertThat(AliasText.sameItem("キャベツ※", "キャベツ")).isTrue();
            assertThat(AliasText.sameItem("＊キャベツ", "キャベツ")).isTrue();
            assertThat(AliasText.sameItem("キャベツ(1/2)", "キャベツ12")).isTrue();
        }

        @Test
        @DisplayName("前後や途中の空白")
        void whitespace_ignored() {
            assertThat(AliasText.sameItem("  キャベツ ", "キャベツ")).isTrue();
            assertThat(AliasText.sameItem("PB 牛乳", "pb牛乳")).isTrue();
        }
    }

    @Nested
    @DisplayName("別物として区別してほしいもの")
    class ShouldNotMatch {

        @Test
        @DisplayName("内容量の数字が違えば別の商品")
        void different_size_is_different_item() {
            // ここを寄せてしまうと、1000ml を 500ml として在庫に積む事故になる
            assertThat(AliasText.sameItem("牛乳500", "牛乳1000")).isFalse();
        }

        @Test
        @DisplayName("そもそも違う品名")
        void different_name() {
            assertThat(AliasText.sameItem("キャベツ", "レタス")).isFalse();
        }
    }

    @Nested
    @DisplayName("端の場合")
    class EdgeCases {

        @Test
        @DisplayName("null と空文字は照合の対象にしない")
        void null_and_blank() {
            assertThat(AliasText.normalize(null)).isNull();
            assertThat(AliasText.normalize("")).isNull();
            assertThat(AliasText.normalize("   ")).isNull();
            // 記号だけの行（「※」だけ等）も、寄せる先が無いので対象外にする
            assertThat(AliasText.normalize("※※")).isNull();
        }

        @Test
        @DisplayName("null 同士は「同じ」にしない")
        void null_is_not_equal_to_null() {
            // 品名が読めなかった行どうしが勝手に紐付くと、まったく別の食材が混ざる
            assertThat(AliasText.sameItem(null, null)).isFalse();
        }

        @Test
        @DisplayName("何度通しても結果が変わらない")
        void idempotent() {
            String once = AliasText.normalize("ｷｬﾍﾞﾂ ※");
            assertThat(AliasText.normalize(once)).isEqualTo(once);
        }
    }
}
