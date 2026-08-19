package jp.komeko.order.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.OrderUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 起動時に走る仕込みの<b>順番</b>を固定するテスト。
 *
 * <p><b>なぜ必要か</b><br>
 * {@link ApplicationRunner} を実装した Bean が複数あるとき、
 * <b>Spring は実行順を保証しません。</b>
 * 順序を指定しないと全部が「最も低い優先度」で並び、
 * 最終的な順番は Bean が見つかった順＝クラスパスを走査した順になります。
 * これは環境が変われば変わりうる値です。
 *
 * <p><b>実際に壊れました</b><br>
 * 手元では {@link DataSeeder}（卓・メニューを作る）が先に走っていたのに、
 * Render のコンテナでは {@link DemoDataSeeder}（伝票を積む）が先に走りました。
 * 土台がまだ無い状態で伝票を作ろうとするので、
 * ログには「0 卓ぶんの伝票を作成」とだけ出て、
 * 公開デモの厨房ボードが空のままになりました。
 *
 * <p><b>例外は出ません。</b>
 * 「対象が見つからなかったので 0 件作った」は、コードとしては正常な動作だからです。
 * 起動もするし、画面も出る。ただ中身が無い。
 * だからテストで順番そのものを固定します。
 *
 * <hr>
 *
 * <h2>なぜ Spring を起動しないのか</h2>
 *
 * <p>はじめはコンテナから {@link ApplicationRunner} を全部もらって
 * 並べ替える形で書きましたが、それでは<b>何も検査できませんでした</b>。
 * {@link DemoDataSeeder} は {@code @Profile({"dev","demo"})} なので、
 * テスト用の {@code test} プロファイルではそもそも Bean が作られません。
 * 「居ないので比べようがない」で素通りするテストは、
 * 通っていても何も守っていないので、いちばん質が悪い。
 *
 * <p>そこで Spring を起動せず、注釈を直接読みます。
 * 守りたいのは「両方に明示的な順序が付いていること」と「その大小関係」で、
 * どちらもクラスを見れば分かります。
 * プロファイルにもコンテナの起動にも左右されず、一瞬で終わります。
 */
@DisplayName("起動時の仕込みは、土台を作ってからデモデータを積む順で走る")
class SeederOrderTest {

    @Test
    @DisplayName("DataSeeder と DemoDataSeeder の両方に明示的な順序が付いている")
    void bothSeedersDeclareAnExplicitOrder() {
        assertThat(OrderUtils.getOrder(DataSeeder.class))
                .as("DataSeeder に @Order が無いと、実行順がクラスパスの走査順まかせになる")
                .isNotNull();

        assertThat(OrderUtils.getOrder(DemoDataSeeder.class))
                .as("DemoDataSeeder に @Order が無いと、実行順がクラスパスの走査順まかせになる")
                .isNotNull();
    }

    @Test
    @DisplayName("土台（DataSeeder）が先、デモデータ（DemoDataSeeder）が後")
    void dataSeederRunsBeforeDemoDataSeeder() {
        int base = OrderUtils.getOrder(DataSeeder.class, Integer.MAX_VALUE);
        int demo = OrderUtils.getOrder(DemoDataSeeder.class, Integer.MAX_VALUE);

        assertThat(base)
                .as("卓とメニューを作る DataSeeder は、伝票を積む DemoDataSeeder より先に走ること。"
                        + "逆になると『0 卓ぶんの伝票を作成』で終わり、公開デモの厨房ボードが空になる")
                .isLessThan(demo);
    }
}
