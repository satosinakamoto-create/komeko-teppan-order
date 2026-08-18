package jp.komeko.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link OrderEventPublisher}（厨房・ホールへの自動更新）のテスト。
 *
 * <p><b>このテストが守っているもの＝「切れた接続を触らない」こと</b><br>
 * 画面を閉じる・タブを切り替える・アプリを再起動する、といった
 * ごくふつうの操作で SSE の接続は切れます。
 * 切れた接続に書き込もうとすると、Tomcat が次の警告を出します。
 *
 * <pre>
 *   A non-container (application) thread attempted to use the AsyncContext
 *   after an error had occurred and the call to AsyncListener.onError()
 *   had returned.
 * </pre>
 *
 * <p>実害は「ログが埋まる」ことですが、埋まると本当のエラーが読めなくなります。
 * しかもこの警告は Tomcat の中から出るので、
 * アプリ側のテストで直接捕まえることはできません。
 * そこで<b>警告が出る条件そのもの</b>——
 * 死んだ接続が一覧に残らないこと、送信の失敗で例外が外へ漏れないこと——を固定します。
 *
 * <p>Spring を起動しない素の JUnit テストです。
 * {@link SseEmitter} は HTTP の接続が無くても作れて、
 * {@code complete()} を呼べば「切れた状態」を再現できます。
 */
@DisplayName("自動更新の配信（OrderEventPublisher）")
class OrderEventPublisherTest {

    private final OrderEventPublisher publisher = new OrderEventPublisher();

    @Nested
    @DisplayName("接続の受付と後片付け")
    class Subscription {

        @Test
        @DisplayName("接続すると数に数えられる")
        void counts() {
            publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_KITCHEN)).isEqualTo(1);
        }

        /**
         * <b>ここで onCompletion を試せない理由（実際にテストが落ちて分かったこと）</b>
         *
         * <p>「{@code emitter.complete()} を呼べば一覧から外れる」と書いてみたら落ちました。
         * {@link SseEmitter} の後片付けコールバックは、
         * <b>本物の HTTP 接続に結びついているときにコンテナが呼ぶもの</b>で、
         * 接続を持たない素のインスタンスに {@code complete()} を呼んでも「完了した」と
         * 記録されるだけで、コールバックは走りません。
         *
         * <p>そのため、切れた接続が一覧から消えることは
         * 「次に送ろうとしたときに失敗して外れる」経路で確かめます
         * （下の「切れた接続への配信」を参照）。
         * 実際の運用でもこの経路が効いていて、
         * コールバックと配信時の後片付けの二重の備えになっています。
         */
        @Test
        @DisplayName("完了した接続へは、もう送れない（後片付けの入口になる）")
        void completedEmitterRejectsSend() {
            SseEmitter emitter = publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
            emitter.complete();

            assertThatCode(() -> emitter.send("直接送ってみる"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("チャンネルは厨房とホールで独立している")
        void channelsAreIndependent() {
            publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_KITCHEN)).isEqualTo(1);
            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_HALL)).isZero();
        }

        @Test
        @DisplayName("誰もつながっていないチャンネルの数を聞いても落ちない")
        void unknownChannelIsZero() {
            assertThat(publisher.connectionCount("だれもいない")).isZero();
        }
    }

    @Nested
    @DisplayName("切れた接続への配信")
    class DeadConnections {

        @Test
        @DisplayName("切れた接続へ配信しても例外が漏れず、一覧からも消える")
        void publishToDeadConnectionIsSafe() {
            // ここが本題。切れた接続へ送ろうとしても、
            // 注文を保存した処理まで巻き込んで落としてはいけない。
            SseEmitter emitter = publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
            emitter.complete();

            assertThatCode(() -> publisher.publishOrderChanged("なにか")).doesNotThrowAnyException();

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_KITCHEN)).isZero();
        }

        @Test
        @DisplayName("切れた接続へ生存確認を送っても例外が漏れず、一覧からも消える")
        void heartbeatToDeadConnectionIsSafe() {
            // 25 秒ごとに動く処理なので、ここで例外が出ると
            // スケジューラが止まり、以後どの画面にも生存確認が届かなくなる。
            SseEmitter emitter = publisher.subscribe(OrderEventPublisher.CHANNEL_HALL);
            emitter.complete();

            assertThatCode(publisher::heartbeat).doesNotThrowAnyException();

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_HALL)).isZero();
        }

        @Test
        @DisplayName("生きている接続は、切れた接続の後片付けに巻き込まれない")
        void liveConnectionSurvivesCleanup() {
            // 1 台が落ちたせいで、残りの画面が更新されなくなるのがいちばん困る。
            SseEmitter dead = publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
            publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
            dead.complete();

            publisher.publishOrderChanged("なにか");

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_KITCHEN)).isEqualTo(1);
        }

        @Test
        @DisplayName("何度配信しても、死んだ接続が数え直されたりしない")
        void repeatedPublishStaysClean() {
            SseEmitter emitter = publisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
            emitter.complete();

            for (int i = 0; i < 5; i++) {
                publisher.publishOrderChanged("なにか" + i);
                publisher.heartbeat();
            }

            assertThat(publisher.connectionCount(OrderEventPublisher.CHANNEL_KITCHEN)).isZero();
        }
    }
}
