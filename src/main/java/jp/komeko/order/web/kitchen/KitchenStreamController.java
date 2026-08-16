package jp.komeko.order.web.kitchen;

import jp.komeko.order.service.OrderEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 厨房ボードへ「注文が入った」「状態が変わった」を押し出すための接続口（SSE）。
 *
 * <p><b>SSE（Server-Sent Events）とは</b><br>
 * ブラウザとサーバの間に接続を 1 本張りっぱなしにして、
 * サーバ側の好きなタイミングでデータを流し込める仕組みです。
 * 「サーバ → ブラウザ」の一方向で足りるこの用途では、
 * WebSocket より仕組みが単純で扱いやすいという利点があります。
 *
 * <p>ブラウザ側は {@code new EventSource('/api/stream/kitchen')} と書くだけで受け取れます
 * （実際のコードは {@code static/js/kitchen.js}）。
 * 接続が切れても EventSource が自動で張り直してくれるのも便利なところです。
 *
 * <p><b>なぜ {@code @RestController} なのか</b><br>
 * {@code @Controller} のメソッドが返す String は「テンプレート名」と解釈されますが、
 * このクラスが返すのは画面ではなくデータの流れ（{@link SseEmitter}）です。
 * {@code @RestController}（= {@code @Controller} + {@code @ResponseBody}）にしておくと、
 * 戻り値がそのままレスポンス本文として扱われることが明示できます。
 *
 * <p><b>{@code produces = text/event-stream}</b><br>
 * SSE は Content-Type が {@code text/event-stream} でなければブラウザが解釈してくれません。
 * ここで明示しておくことで、間に挟まるプロキシにも「これは流しっぱなしの通信だ」と伝わります。
 *
 * <p><b>アクセス制御</b><br>
 * この URL は {@code SecurityConfig} で STAFF / ADMIN 限定にしてあります。
 * 注文内容が流れるため、お客さんの端末からは見えないようにする必要があります。
 * （お客さん向けのサイネージ {@code /api/stream/display} は番号しか流さないので公開）
 */
@RestController
public class KitchenStreamController {

    private final OrderEventPublisher eventPublisher;

    public KitchenStreamController(OrderEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 厨房チャンネルを購読する。
     *
     * <p>返した {@link SseEmitter} を Spring がそのまま持ち続けてくれるので、
     * このメソッド自体はすぐ終わります（リクエストのスレッドは解放されます）。
     * あとは {@link OrderEventPublisher} が注文の変化を検知したタイミングで、
     * 保持しているエミッタ全員へイベントを配ります。
     */
    @GetMapping(path = "/api/stream/kitchen", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventPublisher.subscribe(OrderEventPublisher.CHANNEL_KITCHEN);
    }
}
