package jp.komeko.order.web.kitchen;

import jp.komeko.order.web.kitchen.KitchenController.ElapsedDisplay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厨房ボードの「経過時間をどう見せるか」のテスト。
 *
 * <p><b>このテストが守っているもの＝実店舗の遅延アラート</b><br>
 * 公開デモの見栄えを直すために入れた仕組みなので、
 * <b>実店舗の側にはみ出していないこと</b>を先に固定します。
 * 遅延アラートは「料理が止まっているのに誰も気づいていない」を防ぐ本番の機能で、
 * デモの都合で鈍らせてよいものではありません。
 *
 * <p>そのうえで、公開デモでだけ
 * 「起動時に置かれた背景の注文が夕方には 378 分になり、ボード全面が赤枠になる」
 * という 2026-08-24 の状態に戻らないことを固定します。
 *
 * <p>Spring を起動しない素の JUnit です。判断に必要な材料は
 * 「デモか」「何分たったか」の 2 つだけなので、DB も HTTP も要りません。
 */
@DisplayName("厨房ボードの経過時間の見せ方")
class ElapsedDisplayTest {

    /** 実店舗と同じ設定（デモではない）。閾値は KitchenController の値に合わせている。 */
    private final ElapsedDisplay realShop = new ElapsedDisplay(false, 15, 90);

    /** 公開デモの設定。 */
    private final ElapsedDisplay demo = new ElapsedDisplay(true, 15, 90);

    @Nested
    @DisplayName("実店舗（app.demo-data=false）ではふるまいが変わらない")
    class RealShop {

        @Test
        @DisplayName("何分たっても経過時間は必ず表示する")
        void alwaysShowsElapsedMinutes() {
            // 実店舗のボードから数字が消えると、厨房は「何分待たせているか」を
            // 画面から読めなくなる。デモ用の細工が本番に漏れていないことの確認。
            assertThat(realShop.shows(0)).isTrue();
            assertThat(realShop.shows(14)).isTrue();
            assertThat(realShop.shows(90)).isTrue();
            assertThat(realShop.shows(378)).isTrue();
        }

        @Test
        @DisplayName("15 分以上たてば赤枠になる。何時間たっても赤枠のまま")
        void latenessIsNeverSuppressed() {
            assertThat(realShop.late(14)).isFalse();
            assertThat(realShop.late(15)).isTrue();
            // ここが本題。閉店間際に「6 時間前の注文がまだ受付のまま」を
            // 静かに見逃すようになったら、この修正は失敗している。
            assertThat(realShop.late(90)).isTrue();
            assertThat(realShop.late(378)).isTrue();
        }
    }

    @Nested
    @DisplayName("公開デモ（app.demo-data=true）でだけ、古すぎる注文を外す")
    class Demo {

        @Test
        @DisplayName("見学者がその場で入れた注文は、これまでどおり分数が出て 15 分で赤くなる")
        void freshOrdersBehaveNormally() {
            // 遅延アラートそのものはデモでも見せたい機能。
            // 「デモでは赤くしない」ではなく「古すぎるものだけ外す」が方針。
            assertThat(demo.shows(0)).isTrue();
            assertThat(demo.late(14)).isFalse();
            assertThat(demo.shows(20)).isTrue();
            assertThat(demo.late(20)).isTrue();
        }

        @Test
        @DisplayName("閾値（90 分）を超えた注文は、数字も赤枠も出さない")
        void staleOrdersAreDropped() {
            // 378 分＝ 2026-08-24 に公開デモで実際に出ていた値。
            // 起動時に DemoDataSeeder が置いた注文が、日中ずっと生きている
            // インスタンスの上で夕方までに育った結果。
            assertThat(demo.shows(378)).isFalse();
            assertThat(demo.late(378)).isFalse();
        }

        @Test
        @DisplayName("境界はちょうど 90 分。89 分までは出す")
        void boundary() {
            assertThat(demo.shows(89)).isTrue();
            assertThat(demo.shows(90)).isFalse();
        }

        @Test
        @DisplayName("数字を出さないと決めた注文が赤枠になることはない（表示と枠は必ず連動する）")
        void hiddenNeverTurnsRed() {
            // 「数字は無いのに枠だけ赤い」が出ると、見た人は理由の分からない赤を見る。
            // late() が内側で shows() を呼ぶ構造になっていることの確認。
            for (long minutes : new long[]{90, 100, 378, 1000}) {
                assertThat(demo.shows(minutes)).isFalse();
                assertThat(demo.late(minutes)).isFalse();
            }
        }
    }
}
