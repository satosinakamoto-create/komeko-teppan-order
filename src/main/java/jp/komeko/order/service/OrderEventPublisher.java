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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 厨房画面・ホール画面へ「注文が入った」「状態が変わった」を<b>押し出す</b>仕組み。
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
 *
 * <p><b>切れた接続を触らないことが、このクラスのいちばん大事な仕事です</b><br>
 * 2026-08-19 に、次の警告がログを埋めていました。
 *
 * <pre>
 *   A non-container (application) thread attempted to use the AsyncContext
 *   after an error had occurred and the call to AsyncListener.onError()
 *   had returned. This is not allowed to avoid race conditions.
 * </pre>
 *
 * <p>意味は「もう後片付けが済んだ接続に、アプリ側のスレッドが書き込もうとした」です。
 * 画面を閉じる・タブを切り替える・アプリを再起動する、といった<b>ごくふつうの操作</b>で
 * 接続は切れるので、これは避けられない事故ではなく、想定して書くべきことです。
 * 原因は 2 つありました。
 *
 * <ol>
 *   <li>送信に失敗したとき {@code completeWithError()} を呼んでいた。
 *       失敗した時点で後片付けは終わっているので、これは「済んだ接続を触る」操作そのもの</li>
 *   <li>25 秒ごとの生存確認が、切れた直後の接続に書き込んでいた。
 *       一覧から外す処理と生存確認が同時に走ると、外す前の一覧に向けて送ってしまう</li>
 * </ol>
 *
 * <p>そこで接続ごとに「まだ生きているか」の札（{@link Subscriber#alive}）を持たせ、
 * <b>送る前に必ず確かめる／終わった接続に complete 系を呼ばない</b>ようにしました。
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
     * つながっている画面 1 つぶん。
     *
     * <p><b>なぜ {@link SseEmitter} をそのまま並べないのか</b><br>
     * 「もう切れている」ことを送る前に知りたいからです。
     * {@code SseEmitter} 自身には「生きているか」を聞く方法がありません。
     * 切れたときに呼ばれるコールバックで札を倒しておき、
     * 送る前にその札を見ることで、済んだ接続を触らずに済みます。
     *
     * <p>{@link AtomicBoolean} を使うのは、札を倒すのと見るのが別のスレッドだからです。
     * 倒すのは接続を後片付けするスレッド、見るのは注文を保存したスレッドや
     * 25 秒ごとの生存確認のスレッドで、いつどちらが動くか決まっていません。
     */
    private static final class Subscriber {
        private final SseEmitter emitter;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    /**
     * チャンネル名 → 接続中の画面一覧。
     * 複数スレッドから同時に触られるので、スレッドセーフなコレクションを使う。
     */
    private final Map<String, List<Subscriber>> channels = new ConcurrentHashMap<>();

    /**
     * 画面から接続してきたときに呼ぶ。
     * 返した {@link SseEmitter} をコントローラがそのまま返すと接続が確立します。
     */
    public SseEmitter subscribe(String channel) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(emitter);
        List<Subscriber> list = channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        list.add(subscriber);

        // 接続が終わる 3 つの道すべてで、札を倒して一覧から外す。
        // 外し忘れるとメモリリークになり、札を倒し忘れると死んだ接続へ送り続けてしまう。
        //
        // ここで complete() や completeWithError() を呼ばないこと。
        // これらのコールバックが呼ばれた時点で後片付けは終わっているので、
        // さらに触ると冒頭の AsyncContext 警告が出ます。
        emitter.onCompletion(() -> retire(list, subscriber));
        emitter.onTimeout(() -> retire(list, subscriber));
        emitter.onError(e -> retire(list, subscriber));

        // 接続直後に 1 発送っておくと、ブラウザ側が「つながった」と判定できる
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException | IllegalStateException e) {
            retire(list, subscriber);
        }
        return emitter;
    }

    /** この接続はもう使わない、と記録して一覧から外す。 */
    private void retire(List<Subscriber> list, Subscriber subscriber) {
        subscriber.alive.set(false);
        list.remove(subscriber);
    }

    /** 指定チャンネルへイベントを流す。 */
    public void publish(String channel, String eventName, Object payload) {
        sendToAll(channel, SseEmitter.event().name(eventName).data(payload));
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
        for (String channel : channels.keySet()) {
            sendToAll(channel, SseEmitter.event().comment("ping"));
        }
    }

    /**
     * チャンネルにつながっている全画面へ 1 件送る。
     *
     * <p>送信と後片付けをここ 1 箇所にまとめてあります。
     * 以前は「イベントを配る」「生存確認を送る」で別々に書いていて、
     * 片方だけ直すと、もう片方から死んだ接続へ送り続けてしまう状態でした。
     */
    private void sendToAll(String channel, SseEmitter.SseEventBuilder event) {
        List<Subscriber> list = channels.get(channel);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : list) {
            // 送る前に札を見る。ここが無いと、切れた直後の接続に書き込んでしまう
            if (!subscriber.alive.get()) {
                list.remove(subscriber);
                continue;
            }
            try {
                subscriber.emitter.send(event);
            } catch (Exception e) {
                // 画面を閉じた直後などは普通に失敗する。エラー扱いにせず片付けるだけにする。
                // completeWithError() は呼ばない（済んだ接続を触ることになる）
                retire(list, subscriber);
                log.debug("切れた画面を {} から外しました: {}", channel, e.toString());
            }
        }
    }

    /** 監視用：いま何画面つながっているか。 */
    public int connectionCount(String channel) {
        List<Subscriber> list = channels.get(channel);
        return list == null ? 0 : list.size();
    }
}
