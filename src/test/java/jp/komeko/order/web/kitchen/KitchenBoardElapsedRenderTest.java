package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厨房ボードの経過時間の見せ方（<b>実店舗</b>）。
 *
 * <p><b>このテストが守っているもの＝遅延アラート</b><br>
 * 「15 分たったチケットの枠が赤くなる」は本番の機能で、
 * 料理が止まっていることに気づくための最後の砦です。
 *
 * <p>2026-08-26 に、公開デモの見え方を直すための仕組みを入れました
 * （夕方に開くと背景の注文が全部「378 分経過」になり、ボード全面が赤枠になっていた）。
 * その細工が<b>実店舗まで静かに漏れていないこと</b>をここで押さえます。
 * デモの都合で本番のアラートを鈍らせるのは、直した問題より重い事故です。
 *
 * <p>{@code @Transactional} を付けないのは、{@code open-in-view: false} の本番と
 * 同じトランザクション境界で描画させるためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("厨房ボードの経過時間の見せ方（実店舗）")
class KitchenBoardElapsedRenderTest extends KitchenBoardElapsedRenderSupport {

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("6 時間前の注文も、分数がそのまま出て赤枠になる")
    void keepsTheLateAlertForVeryOldOrders() throws Exception {
        backdate(STALE_MINUTES);

        Ticket ticket = renderSingleTicket();

        // 実店舗のボードから数字が消えると、厨房は何分待たせているかを画面から読めなくなる。
        assertThat(ticket.time()).isEqualTo(STALE_MINUTES + " 分");
        assertThat(ticket.late()).isTrue();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("15 分たてば赤枠になる")
    void marksLateOrders() throws Exception {
        backdate(20);

        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time()).isEqualTo("20 分");
        assertThat(ticket.late()).isTrue();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("入ったばかりの注文は赤枠にならない")
    void doesNotMarkFreshOrders() throws Exception {
        Ticket ticket = renderSingleTicket();

        assertThat(ticket.time()).isEqualTo("0 分");
        assertThat(ticket.late()).isFalse();
    }
}
