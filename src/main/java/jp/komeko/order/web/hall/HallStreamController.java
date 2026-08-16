package jp.komeko.order.web.hall;

import jp.komeko.order.service.OrderEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ホール画面へ「注文が入った」「状態が変わった」を押し出すための接続口（SSE）。
 *
 * <p><b>SSE（Server-Sent Events）とは</b><br>
 * ブラウザとサーバの間に接続を 1 本張りっぱなしにして、
 * サーバ側の好きなタイミングでデータを流し込める仕組みです。
 * 「サーバ → ブラウザ」の一方向で足りるこの用途では、
 * WebSocket より単純で扱いやすいという利点があります。
 *
 * <p>ブラウザ側は {@code new EventSource('/api/stream/hall')} と書くだけで受け取れます
 * （実際のコードは {@code static/js/hall.js}）。
 * 接続が切れても EventSource が自動で張り直してくれるのも便利なところです。
 *
 * <p><b>なぜ厨房と別のチャンネルなのか</b><br>
 * 流れてくるイベントの名前は同じ（order-changed）ですが、
 * 受け取ったあとの振る舞いが違います。厨房は音を鳴らして急かす画面、
 * ホールは金額を静かに更新する画面です。
 * チャンネルを分けておくと、あとから「ホールにだけ会計イベントも流す」
 * といった拡張がしやすくなります。
 * チャンネル名の定数は {@link OrderEventPublisher#CHANNEL_HALL} にあります。
 *
 * <p><b>なぜ {@code @RestController} なのか</b><br>
 * {@code @Controller} のメソッドが返す String は「テンプレート名」と解釈されますが、
 * このクラスが返すのは画面ではなくデータの流れ（{@link SseEmitter}）です。
 * {@code @RestController}（= {@code @Controller} + {@code @ResponseBody}）にしておくと、
 * 戻り値がそのままレスポンス本文として扱われることが明示できます。
 *
 * <p><b>アクセス制御</b><br>
 * {@code /api/stream/**} は {@code SecurityConfig} で STAFF / ADMIN 限定にしてあります。
 * 卓名や注文内容が流れるため、お客さまの端末からは見えないようにする必要があります。
 */
@RestController
public class HallStreamController {

    private final OrderEventPublisher eventPublisher;

    public HallStreamController(OrderEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * ホールチャンネルを購読する。
     *
     * <p>返した {@link SseEmitter} を Spring がそのまま持ち続けてくれるので、
     * このメソッド自体はすぐ終わります（リクエストのスレッドは解放されます）。
     * あとは {@link OrderEventPublisher} が注文の変化を検知したタイミングで、
     * 保持しているエミッタ全員へイベントを配ります。
     *
     * <p>{@code produces = text/event-stream} を明示しているのは、
     * この Content-Type でないとブラウザが SSE として解釈してくれないためです。
     */
    @GetMapping(path = "/api/stream/hall", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventPublisher.subscribe(OrderEventPublisher.CHANNEL_HALL);
    }
}
