package jp.komeko.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 厨房画面・サイネージへ「注文が入った」「状態が変わった」を<b>押し出す</b>仕組み。
 *
 * <p><b>SSE（Server-Sent Events）とは</b><br>
 * ブラウザからサーバへ 1 本の接続を張りっぱなしにして、
 * サーバ側の好きなタイミングでデータを流し込める仕組みです。
 * WebSocket より単純で、「サーバ → ブラウザ」の一方向で足りるこの用途にぴったりです。
 * ブラウザ側は {@code new EventSource('/api/stream/kitchen')} と書くだけで受け取れます。
 *
 * <p>接続中の {@link SseEmitter} をチャンネルごとに保持しておき、
 * イベント発生時に全員へ配ります。
 *
 * <p><b>制約</b>：この方式はアプリを 1 台で動かす前提です。
 * 複数台に負荷分散すると「別のサーバにつながっている画面」に届きません。
 * その場合は Redis Pub/Sub などを挟む必要があります（docs/仕様.md に記載）。
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /** 厨房ボード用チャンネル。 */
    public static final String CHANNEL_KITCHEN = "kitchen";
    /** ホール（伝票・会計）画面用チャンネル。 */
    public static final String CHANNEL_HALL = "hall";

    /** 接続が切れたとみなすまでの時間（ミリ秒）。長めにしておく。 */
    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /**
     * チャンネル名 → 接続中のエミッタ一覧。
     * 複数スレッドから同時に触られるので、スレッドセーフなコレクションを使う。
     */
    private final Map<String, List<SseEmitter>> channels = new ConcurrentHashMap<>();

    /**
     * 画面から接続してきたときに呼ぶ。
     * 返した {@link SseEmitter} をコントローラがそのまま返すと接続が確立します。
     */
    public SseEmitter subscribe(String channel) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        List<SseEmitter> list = channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        // 接続が終わったら必ずリストから外す（外し忘れるとメモリリークになる）
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> {
            list.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> list.remove(emitter));

        // 接続直後に 1 発送っておくと、ブラウザ側が「つながった」と判定できる
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            list.remove(emitter);
        }
        return emitter;
    }

    /** 指定チャンネルへイベントを流す。 */
    public void publish(String channel, String eventName, Object payload) {
        List<SseEmitter> list = channels.get(channel);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                // 画面を閉じた直後などは普通に失敗する。エラー扱いにせず片付けるだけにする。
                list.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** 厨房とホールの両方へ流す（注文の増減は伝票の金額にも影響するため）。 */
    public void publishOrderChanged(Object payload) {
        publish(CHANNEL_KITCHEN, "order-changed", payload);
        publish(CHANNEL_HALL, "order-changed", payload);
    }

    /**
     * 定期的にコメント行を送って接続を維持する。
     *
     * <p>間に挟まるプロキシやモバイル回線は「無通信が続いた接続」を勝手に切ります。
     * 25 秒ごとに合図を送ることでそれを防ぎます（ハートビート）。
     */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        channels.forEach((channel, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    list.remove(emitter);
                }
            }
        });
    }

    /** 監視用：いま何画面つながっているか。 */
    public int connectionCount(String channel) {
        List<SseEmitter> list = channels.get(channel);
        return list == null ? 0 : list.size();
    }
}
