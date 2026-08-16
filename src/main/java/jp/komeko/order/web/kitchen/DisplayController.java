package jp.komeko.order.web.kitchen;

import jp.komeko.order.service.OrderEventPublisher;
import jp.komeko.order.service.OrderService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 店頭サイネージ（番号呼び出し画面）のコントローラ。
 *
 * <p><b>この画面の位置づけ</b><br>
 * カウンター横のディスプレイに朝から晩まで出しっぱなしにする画面です。
 * 誰も操作しないので、
 * <ul>
 *   <li>ログイン不要（{@code SecurityConfig} で {@code /display} を permitAll にしている）</li>
 *   <li>ページを再読み込みしない（チラつくと店内から丸見えなので嫌われる）</li>
 *   <li>サーバ側から変化を押し出す（SSE）</li>
 * </ul>
 * という 3 点を守って作っています。
 *
 * <p><b>なぜ 3 つの URL に分かれているのか</b>
 * <table border="1">
 *   <tr><th>URL</th><th>返すもの</th><th>役割</th></tr>
 *   <tr><td>GET /display</td><td>HTML</td><td>画面そのもの。最初の 1 回だけ読み込まれる</td></tr>
 *   <tr><td>GET /api/stream/display</td><td>SSE</td><td>「注文に動きがあったよ」という<b>合図</b>だけを流す</td></tr>
 *   <tr><td>GET /api/public/display</td><td>JSON</td><td>合図を受けたブラウザが取りに来る<b>中身</b></td></tr>
 * </table>
 *
 * <p>合図と中身を分けているのは、SSE で番号一覧そのものを流すより単純だからです。
 * 途中で接続が切れて合図を取りこぼしても、次の合図やポーリングで JSON を取り直せば
 * 必ず正しい状態に追いつけます（「差分」ではなく「現在の全量」を返す設計）。
 *
 * <p><b>{@code @Controller} と {@code @RestController} の違い</b><br>
 * {@code @Controller} のメソッドが返す String は「テンプレート名」として扱われます。
 * 同じクラスの中で JSON も返したいので、JSON のメソッドにだけ
 * {@code @ResponseBody}（＝戻り値を画面名ではなく本文として書き出す）を付けています。
 */
@Controller
public class DisplayController {

    /**
     * 依存はコンストラクタで受け取ります（コンストラクタインジェクション）。
     *
     * <p>フィールドに {@code @Autowired} を付ける書き方もありますが、
     * コンストラクタで受け取ると {@code final} が付けられて「あとから差し替え不能」になり、
     * テストのときも {@code new DisplayController(...)} で普通に組み立てられます。
     * Spring では引数が 1 つのコンストラクタが 1 個だけなら {@code @Autowired} は省略できます。
     */
    private final OrderService orderService;
    private final OrderEventPublisher eventPublisher;

    public DisplayController(OrderService orderService, OrderEventPublisher eventPublisher) {
        this.orderService = orderService;
        this.eventPublisher = eventPublisher;
    }

    // ========================================================================
    //  1. サイネージ本体（HTML）
    // ========================================================================

    /**
     * 番号呼び出しサイネージを表示する。
     *
     * <p>初期表示ぶんの番号はここでサーバ側が描き込みます。
     * JavaScript が動く前の一瞬でも正しい番号が出ますし、
     * 万一 JS が止まっても「その瞬間の番号」は表示され続けます。
     *
     * <p>店舗名（{@code ${shop}}）は {@code GlobalModelAttributes}（@ControllerAdvice）が
     * 全画面に自動で入れてくれるので、ここで詰め直す必要はありません。
     */
    @GetMapping("/display")
    public String display(Model model) {
        model.addAttribute("cookingNumbers", orderService.cookingNumbers());
        model.addAttribute("readyNumbers", orderService.readyNumbers());
        return "kitchen/display";
    }

    // ========================================================================
    //  2. 変化の合図（SSE）
    // ========================================================================

    /**
     * サイネージ向けの SSE（Server-Sent Events）接続。
     *
     * <p>ブラウザが {@code new EventSource('/api/stream/display')} と書くと、
     * ここで返した {@link SseEmitter} との間に「開きっぱなしの片道通信路」ができます。
     * 注文の状態が変わると {@code OrderService} → {@code OrderEventPublisher} 経由で
     * {@code order-changed} イベントが流れてきます。
     *
     * <p><b>{@code produces} を指定する理由</b><br>
     * SSE は {@code Content-Type: text/event-stream} でなければブラウザが解釈しません。
     * {@link MediaType#TEXT_EVENT_STREAM_VALUE} を指定して明示します。
     *
     * <p><b>{@code SseEmitter} と {@code @ResponseBody}</b><br>
     * {@code SseEmitter} は Spring MVC が特別扱いする戻り値型なので、実は
     * {@code @ResponseBody} が無くても画面名とは解釈されません。
     * ただし「これは本文を書き出すメソッドだ」と読む人に伝わるよう明示的に付けています。
     *
     * <p>接続の後片付け（タイムアウト・切断時のリスト除去）は
     * {@code OrderEventPublisher#subscribe} 側でやってくれるので、ここは 1 行で済みます。
     */
    @GetMapping(path = "/api/stream/display", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream() {
        return eventPublisher.subscribe(OrderEventPublisher.CHANNEL_DISPLAY);
    }

    // ========================================================================
    //  3. 表示する中身（JSON）
    // ========================================================================

    /**
     * いま画面に出すべき番号の一覧を JSON で返す。
     *
     * <pre>
     *   {"cooking":[101,102],"ready":[99]}
     * </pre>
     *
     * <p>お客さんの個人情報（名前・注文内容・金額）は一切含めません。
     * この URL はログイン不要（{@code /api/public/**} が permitAll）なので、
     * 「店内の掲示板に貼っても平気な情報」＝番号だけに絞るのが鉄則です。
     *
     * <p>{@code Map.of(...)} で作った Map は Jackson が自動で JSON に変換します。
     * 項目が増えて型を固定したくなったら record を作って返すのが次の一手です。
     */
    @GetMapping(path = "/api/public/display", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, List<Integer>> numbers() {
        return Map.of(
                "cooking", orderService.cookingNumbers(),
                "ready", orderService.readyNumbers());
    }
}
