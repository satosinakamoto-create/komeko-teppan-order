package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同じ選択肢を複数選んだときの、金額と表示。
 *
 * <p><b>このテストが守っているもの＝「ソース ×3」の代金を取りこぼさないこと</b><br>
 * たこ焼きの「4 種類お選びください」は、いま違う味を 4 つ選ぶ設定です。
 * 「ソース 3 つ・ごま油 1 つ」のような頼まれ方があるか店主に確認中で、
 * <b>お客さま側の画面はまだ作っていません</b>（2026-09-05）。
 * 保存の形（{@link OrderLineOption#getQuantity()}）だけ先に整えてあります。
 *
 * <p>いちばん怖いのは<b>金額の取りこぼし</b>です。
 * {@code OrderLine#recalculate} が 1 個あたりの単価をそのまま足していると、
 * ソース 3 つぶんが 1 つぶんの代金しか乗りません。
 * 画面は動いているように見えるので、伝票を検算するまで気づけません。
 *
 * <p>Spring を起動しない素の JUnit です（CLAUDE.md「速さが正義」）。
 */
@DisplayName("オプションの個数")
class OrderLineOptionQuantityTest {

    /** 「たこ焼 8個」1 皿。味は +50 円のものがある想定。 */
    private static OrderLine takoyaki() {
        return new OrderLine(1L, "たこ焼 8個", 1190, 1, 10);
    }

    @Nested
    @DisplayName("金額")
    class 金額 {

        @Test
        @DisplayName("個数を指定しない従来の呼び方は、1 個ぶんのまま")
        void 既定は1個() {
            OrderLineOption o = new OrderLineOption(10L, "お味", "ソース", 50);

            // 既存の注文はすべてこの形。意味が変わっていないことを固定する
            assertThat(o.getQuantity()).isEqualTo(1);
            assertThat(o.getSubtotal()).isEqualTo(50);
        }

        @Test
        @DisplayName("3 つ選んだら、追加料金も 3 つぶん")
        void 個数ぶん加算される() {
            OrderLineOption o = new OrderLineOption(10L, "お味", "ソース", 50, 3);

            assertThat(o.getSubtotal()).isEqualTo(150);
        }

        @Test
        @DisplayName("明細の単価と小計に、個数ぶんが乗る")
        void 明細の金額に反映される() {
            OrderLine line = takoyaki();
            line.addOption(new OrderLineOption(10L, "お味", "ソース", 50, 3));
            line.addOption(new OrderLineOption(11L, "お味", "ごま油＆塩", 50, 1));
            line.recalculate();

            // 1190 + (50×3) + (50×1) = 1390
            // ここが getExtraPrice のままだと 1190 + 50 + 50 = 1290 になり、
            // ソース 2 つぶん（100 円）を取りこぼす
            assertThat(line.getUnitPrice()).isEqualTo(1390);
            assertThat(line.getLineTotal()).isEqualTo(1390);
        }

        @Test
        @DisplayName("0 や負の個数で作られても 1 に倒す")
        void 個数は1未満にならない() {
            // 「選んでいない」は行そのものが無い状態で表す。
            // 個数 0 の行があると、選んだのか選んでいないのかが判別できなくなる
            assertThat(new OrderLineOption(10L, "お味", "ソース", 50, 0).getQuantity()).isEqualTo(1);
            assertThat(new OrderLineOption(10L, "お味", "ソース", 50, -2).getQuantity()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("表示")
    class 表示 {

        @Test
        @DisplayName("1 個のときは ×1 を出さない")
        void 個数1なら数を出さない() {
            OrderLine line = takoyaki();
            line.addOption(new OrderLineOption(10L, "お味", "ソース", 50));

            // ここで ×1 と出すと、いまある注文の見え方まで変わってしまう
            assertThat(line.getOptionSummary()).isEqualTo("ソース");
        }

        @Test
        @DisplayName("2 個以上のときだけ「ソース ×3」と出す")
        void 個数2以上なら数を出す() {
            OrderLine line = takoyaki();
            line.addOption(new OrderLineOption(10L, "お味", "ソース", 50, 3));
            line.addOption(new OrderLineOption(11L, "お味", "ごま油＆塩", 50, 1));

            // 厨房のチケットに同じ字が 3 行並ばないよう、1 行にまとめる
            assertThat(line.getOptionSummary()).isEqualTo("ソース ×3 / ごま油＆塩");
        }
    }

    @Nested
    @DisplayName("グループの設定")
    class グループの設定 {

        @Test
        @DisplayName("複数選べる設定は、既定で切れている")
        void 既定はオフ() {
            OptionGroup g = new OptionGroup("お味（4種類お選びください）", 4, 4, 10);

            // ここが true に変わると、min/max の意味が「何種類」から「何個」に変わる。
            // 既存のグループが黙って別の検査に切り替わらないよう、既定は必ず false
            assertThat(g.isAllowDuplicate()).isFalse();
        }
    }
}
