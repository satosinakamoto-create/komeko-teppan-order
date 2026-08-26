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
@TestPropertySource(properties = "app.demo-data=true")
@DisplayName("厨房ボードの経過時間の見せ方（公開デモ）")
class KitchenBoardDemoElapsedRenderTest extends KitchenBoardElapsedRenderSupport {

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("実態と合わなくなった注文は、経過時間の欄ごと出さない")
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

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("デモでは赤枠を出さない。赤くなる時間に達したら、数字のほうが先に消える")
    void latenessNeverAppearsInDemo() throws Exception {
        // 15 分＝実店舗なら赤枠が点く時間。デモではここで数字が消えるので赤にならない。
        backdate(20);

        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time()).isNull();
        assertThat(ticket.late()).isFalse();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("赤枠の条件に達する 1 分前までは、まだ数字が出ている")
    void minutesStillShowJustBeforeTheThreshold() throws Exception {
        backdate(14);

        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time()).isEqualTo("14 分");
        assertThat(ticket.late()).isFalse();
    }
}
