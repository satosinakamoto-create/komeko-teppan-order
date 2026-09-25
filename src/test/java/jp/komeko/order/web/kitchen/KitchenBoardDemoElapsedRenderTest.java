package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厨房ボードの経過時間の見せ方（<b>公開デモ</b>）。
 *
 * <p><b>このテストが守っているもの＝公開デモの第一印象</b><br>
 * デモの背景の注文は {@code DemoDataSeeder} が起動時に一括で作ります。
 * ところが公開デモのインスタンスは cron に 10 分おきに叩かれて日中ずっと生き続けるため、
 * 夕方に開くと同じ 6 件が「378 分経過」になり、<b>ボード全面が赤枠</b>になっていました。
 * 見せたいのは「いま回っている厨房」なのに、
 * 「6 時間放置された注文が並ぶ厨房」が出てしまい、意図と正反対でした（2026-08-24）。
 *
 * <p>方針は「デモでは遅延アラートを切る」ではなく
 * <b>「古すぎて実態と合わなくなった注文だけを外す」</b>です。
 * 見学者がその場で入れた注文は経過時間が正しいので、
 * 15 分たてば赤くなるところまで含めて機能として見せます。
 * 最後のテストがその線引きを守っています。
 *
 * <p>{@code app.guest-login} は {@code KitchenController} がコンストラクタで受け取る値で、
 * リクエストごとには切り替えられません。そのため設定違いの別クラスに分けています
 * （段取りは {@link KitchenBoardElapsedRenderSupport}、実店舗側は
 * {@link KitchenBoardElapsedRenderTest}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.demo-data=true",
        // 見ているのは経過時間の表示だけ。過去 1 年ぶんの帳簿を書く必要はない
        "app.demo-history-months=1"})
@DisplayName("厨房ボードの経過時間の見せ方（公開デモ）")
class KitchenBoardDemoElapsedRenderTest extends KitchenBoardElapsedRenderSupport {

    /**
     * ★ 保険としてのしきい値。
     *
     * <p>引き直しが止まったときだけ効きます。効いていれば 18 分を超えないので、
     * ここは通りません。止まったときに全面赤で固まるより、
     * 数字が消えるほうがまだましだ、という位置づけです。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("引き直しが止まって古くなりすぎた注文は、経過時間の欄ごと出さない")
    void staleOrdersAreNeutralised() throws Exception {
        backdate(STALE_MINUTES);

        Ticket ticket = renderSingleTicket();

        // ラベル（「デモ用」など）も置きません。理由は
        // KitchenBoardElapsedRenderSupport#renderSingleTicket のコメントに書いてあります。
        assertThat(ticket.time()).isNull();
        assertThat(ticket.late()).isFalse();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("見学者がその場で入れた注文は、これまでどおり分数が出る")
    void freshOrdersStillShowMinutes() throws Exception {
        Ticket ticket = renderSingleTicket();

        // 経過時間の表示そのものは見せたい機能なので、デモでも消さない。
        assertThat(ticket.time()).isEqualTo("0 分");
        assertThat(ticket.late()).isFalse();
    }

    /**
     * ★★ デモでも赤い印は出る（2026-09-23・店主の指示）。
     *
     * <p>ここは長いあいだ逆を固定していました。
     * しきい値を遅延と同じ 10 分にしてあったので、
     * <b>赤くなる条件に達した瞬間に数字のほうが消え</b>、赤は構造的に出ませんでした。
     *
     * <p>そうしていた理由は「背景の注文が同時に歳を取り、全面赤になる」ことでした。
     * ですがそれは<b>原因ではなく症状</b>で、しきい値はそれを隠していただけです。
     * いま {@code DemoDataSeeder.refreshElapsedTimes()} が 2 分おきに時刻を
     * 引き直すので、種データは歳を取りません。原因が消えたので赤を戻せます。
     *
     * <p>遅延の表示はこのシステムの機能の 1 つです。
     * 公開デモで一度も見られないのは損でした。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★★ デモでも、遅れている注文は赤い印が出る")
    void latenessAppearsInDemo() throws Exception {
        // 実店舗なら赤くなる時間。デモでも同じように出ること。
        backdate(20);

        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time())
                .as("★★ 数字が消えている。しきい値が遅延と同じ値に戻っていないか")
                .isEqualTo("20 分");
        assertThat(ticket.late())
                .as("★★ 赤い印が出ていない")
                .isTrue();
    }

    /**
     * ★ 分数を直書きしないこと（2026-09-23）。
     *
     * <p>以前は {@code backdate(14)} と {@code "14 分"} を直に書いていました。
     * しきい値を 15 → 10 に変えた（設計の決定「10 分経ったら赤」）とたんに、
     * 14 分が<b>境界の向こう側</b>になってこのテストだけ落ちました。
     * 守りたいのは「しきい値の 1 分前はまだ数字が出る」という関係なので、
     * 定数から導きます。次にしきい値が動いても、ここは動きません。
     */
    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("赤の条件に達する 1 分前までは、まだ数字が出ている")
    void minutesStillShowJustBeforeTheThreshold() throws Exception {
        int justBefore = LATE_MINUTES - 1;
        backdate(justBefore);

        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time()).isEqualTo(justBefore + " 分");
        assertThat(ticket.late()).isFalse();
    }
}
