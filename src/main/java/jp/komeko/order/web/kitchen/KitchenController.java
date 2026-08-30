package jp.komeko.order.web.kitchen;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.dto.KitchenBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厨房（キッチン）の画面を担当するコントローラ。
 *
 * <p><b>イートイン（卓番 QR）方式での厨房ボード</b><br>
 * このお店はテーブルでご注文をいただく居酒屋なので、
 * 厨房が知りたいのは「何番のお客さまを呼ぶか」ではなく
 * <b>「焼き上がった料理をどの卓へ運ぶか」</b>です。
 * そのため画面の主役は注文番号ではなく<b>卓名</b>（3番テーブル／カウンター2 など）にしています。
 * 注文番号は厨房と伝票を突き合わせるときの目印として小さく併記するだけです。
 *
 * <p><b>コントローラの役割</b><br>
 * 「HTTP のリクエストを受け取り → サービスに仕事を頼み → 画面に渡す値を用意する」だけの
 * 交通整理係です。金額の計算や状態遷移のルールといった<b>業務のルールは書きません</b>。
 * それらは {@link OrderService} など Service 側に置いてあります。
 * こう分けておくと、あとから「スマホアプリ用の API も生やしたい」となったときに
 * Service をそのまま使い回せます。
 *
 * <p><b>{@code @Controller} と {@code @RestController} の違い</b><br>
 * {@code @Controller} のメソッドが返す String は「テンプレート（HTML）の名前」です。
 * {@code "kitchen/board"} を返すと {@code templates/kitchen/board.html} が描画されます。
 * 一方 {@code @RestController} は戻り値をそのまま JSON などの本文として返します
 * （SSE を返す {@link KitchenStreamController} がそちらです）。
 *
 * <p><b>{@code @RequestMapping("/kitchen")}</b><br>
 * クラスに付けると、中のメソッドの URL すべてに共通の接頭辞が付きます。
 * {@code @PostMapping("/orders/{id}/status")} は実際には
 * {@code POST /kitchen/orders/{id}/status} になります。
 *
 * <p><b>PRG（Post → Redirect → Get）という作法</b><br>
 * 更新系（POST）の処理のあとは、HTML を直接返さず必ずリダイレクトします。
 * そうしないと、処理後の画面でブラウザの「再読み込み」を押したときに
 * 同じ POST がもう一度飛び、二重に状態を進めてしまいます。
 * 厨房は忙しい現場で連打も起きるので、この作法は特に大事です。
 */
@Controller
@RequestMapping("/kitchen")
public class KitchenController {

    private static final Logger log = LoggerFactory.getLogger(KitchenController.class);

    /** この分数以上たった注文は「遅れぎみ」として画面で赤くする。 */
    private static final long LATE_MINUTES = 15;

    /**
     * <b>公開デモでだけ</b>「この経過時間はもう実態を表していない」とみなす分数。
     *
     * <p><b>なぜこれが要るのか</b><br>
     * 公開デモの背景に並んでいる注文は {@code DemoDataSeeder} が
     * <b>アプリの起動時に一括で</b>作ります（{@code Order.createdAt} はその時刻）。
     * ところが公開デモのインスタンスは cron に 10 分おきに叩かれて
     * 日中ずっと生き続けるため、夕方に開くと同じ 6 件が
     * 「378 分経過」になり、<b>ボード全面が赤枠</b>になります。
     * 見せたいのは「いま回っている厨房」なのに、
     * 「6 時間放置された注文が並ぶ厨房」が出てしまい、意図と正反対です。
     *
     * <p><b>なぜ {@link #LATE_MINUTES} と同じ値なのか＝デモでは赤枠を出さない</b><br>
     * 最初は 90 分（遅延閾値の 6 倍）にしていました。見学者が自分で入れた注文が
     * 15 分で赤くなる様子まで機能として見せたかったからです。
     * <b>これは間違いでした。</b>背景の 6 件は同時に作られて同時に歳を取るので、
     * 起動 +15 分〜+90 分のあいだ<b>全 6 枚が一斉に赤くなります</b>。
     * 毎朝 8:00 に起こされるため、午前に開いた人は結局
     * 「放置された注文が並ぶ厨房」を見ることになり、直したはずの問題がそのまま残っていました。
     * さらに、その赤は 90 分ちょうどで予告なく消えます。
     * <b>警告が勝手に消える画面は、警告そのものの信頼を落とします。</b>
     *
     * <p>そこで割り切りました。<b>公開デモでは遅延アラートを出しません。</b>
     * 背景の注文は必ず時間が経つので、デモにおける赤枠は「遅れている」ではなく
     * 「デモのデータが古い」の意味にしかなりません。
     * 意味の違うものを同じ赤で出すくらいなら、出さないほうが正確です。
     * 値を {@link #LATE_MINUTES} に揃えると、赤くなる条件に達した瞬間に
     * 数字のほうが消えるので、<b>赤枠は構造的に発生しません</b>。
     *
     * <p><b>なぜ閾値という間接的な判定なのか</b><br>
     * 本来は「シーダーが置いた注文か」を直接持てば正確です。しかし本番は
     * {@code ddl-auto: validate} で Flyway も未導入のため、
     * 目印の列を足す＝スキーマ変更はできません（CLAUDE.md）。
     * 列を足さずに区別できる材料は経過時間しか無いので、閾値で線を引いています。
     *
     * <p>結果として「見学者が 15 分前に入れた注文」も数字が消えますが、
     * デモで見せたいのは注文が厨房に届く連携そのもので、経過時間ではありません。
     * <b>実店舗（{@code app.demo-data=false}）の遅延アラートは一切変わりません。</b>
     */
    private static final long DEMO_STALE_MINUTES = LATE_MINUTES;

    /**
     * 店側からキャンセルしたときに既定で記録する理由。
     *
     * <p>チケット 1 枚 1 枚に理由の入力欄を置くとボードが狭くなり、
     * さらに入力中に自動リロードが走ると書きかけの文字が消えてしまいます。
     * そこで画面からは確認ダイアログだけを挟み、理由はこの既定値を送っています。
     */
    private static final String DEFAULT_CANCEL_REASON = "店舗都合";

    private final OrderService orderService;
    private final MenuService menuService;
    private final ShopSettingService shopSettingService;

    /**
     * デモ用の背景データが入っているか（環境変数 {@code APP_DEMO_DATA=true}）。
     *
     * <p><b>なぜ {@code app.guest-login} ではないのか</b><br>
     * 最初は {@code app.guest-login} で切っていました。公開デモではこの 2 つが
     * 同時に true になるので、動いてはいました。<b>ですが原因はそちらではありません。</b>
     * 経過時間を狂わせているのは {@link jp.komeko.order.seed.DemoDataSeeder} が
     * 背景の注文を置くことで、そのスイッチは {@code app.demo-data} です。
     * {@code app.guest-login} はログイン画面とゲスト権限の話で、別の関心事です。
     *
     * <p>実際に食い違う組み合わせがあります。{@code tools\run.ps1 -Demo} は
     * dev プロファイルで {@code APP_DEMO_DATA=true} <b>だけ</b>を立てるので、
     * guest-login で切っていると<b>手元では背景注文が出るのに抑制が効かず</b>、
     * 直したはずの全面赤枠がそのまま再現していました。
     *
     * <p>判断の材料は、原因を持っているスイッチから取ります。
     * 相関しているだけの値で代用すると、相関が崩れた瞬間に静かに壊れます。
     *
     * <p>なお値はモデル属性ではなく設定値から直接注入します。
     * 画面に配られた値を読み直す形にすると、テンプレートに載せ忘れた瞬間に
     * 「実店舗扱い」へ静かに倒れるためです。
     */
    private final boolean demoMode;

    /**
     * コンストラクタインジェクション。
     *
     * <p>Spring は「このクラスを作るのにこれらのサービスが要るんだな」と判断して、
     * 自動で渡してくれます（DI＝依存性の注入）。
     * フィールドに {@code @Autowired} を付ける書き方もありますが、
     * コンストラクタで受け取る形にすると
     * <ul>
     *   <li>フィールドを {@code final} にできる（あとから差し替わらない＝安全）</li>
     *   <li>テストで自分で {@code new} するときに何が必要か一目で分かる</li>
     * </ul>
     * という利点があるため、現在はこちらが推奨されています。
     * 引数が 1 つでも複数でも、コンストラクタが 1 つだけなら {@code @Autowired} は不要です。
     */
    public KitchenController(OrderService orderService,
                             MenuService menuService,
                             ShopSettingService shopSettingService,
                             @Value("${app.demo-data:false}") boolean demoMode) {
        this.orderService = orderService;
        this.menuService = menuService;
        this.shopSettingService = shopSettingService;
        this.demoMode = demoMode;
    }

    // ========================================================================
    //  厨房ボード
    // ========================================================================

    /**
     * 厨房ボード（受付／調理中／提供待ちの 3 レーン）を表示する。
     *
     * <p>{@link Model} に入れた値が、そのままテンプレート側で
     * {@code ${board}} のように参照できます。
     *
     * <p><b>「同卓 ◯件」を数えているのはなぜか</b><br>
     * イートインでは、1 つの卓から何度も追加注文が入ります。
     * 別々のチケットとしてバラバラに焼き上がると、ホールが同じ卓へ
     * 何度も往復することになり、料理も冷めてしまいます。
     * そこで「いまこの卓の注文はボード上に何件あるか」をあらかじめ数えておき、
     * 画面のチケットにバッジとして出しています。
     * 数えるのを画面（Thymeleaf）でやろうとすると式が複雑になり読めなくなるので、
     * <b>数える仕事は Java 側で済ませてから渡す</b>のが定石です。
     *
     * <p><b>なぜ {@code accepting} をここで計算するのか</b><br>
     * 画面には {@code ${shop}}（{@link ShopSetting}）も届いていますが、
     * {@code shop.acceptingOrders} は<b>店長が手で倒した非常ブレーキの状態だけ</b>を表します。
     * 実際にお客さんが注文できるかは、それに加えて開店時刻とラストオーダーにも左右されます
     * （{@link ShopSetting#isOrderAcceptable(LocalDateTime)}）。
     * ブレーキが入っていなくても営業時間外なら注文は通らないので、
     * フラグだけを見て「注文受付中」と出すと<b>止まっているのに気づけません</b>。
     * お客さん側の画面（{@code CartController}）と同じ判定をここでも通し、
     * 店とお客さんで表示が食い違わないようにしています。
     */
    @GetMapping
    public String board(Model model) {
        KitchenBoard board = orderService.kitchenBoard();
        ShopSetting setting = shopSettingService.currentReadOnly();

        model.addAttribute("board", board);
        model.addAttribute("accepting", setting.isOrderAcceptable(LocalDateTime.now()));
        // 3 レーンを「見出し・CSS 修飾クラス・注文リスト」の組にして渡す。
        // テンプレート側で同じチケットの HTML を 3 回コピペしなくて済む。
        //
        // 見出しの 3 つめが「お渡し可」ではなく「提供待ち」なのはイートインだからです。
        // お客さまはカウンターに取りに来ません。焼き上がった料理を卓まで運ぶ、
        // その「運ぶのを待っている」状態を表す言葉にしています。
        // （OrderStatus.READY という enum の名前自体は他の画面でも使うので変えません。
        //   画面に出す文言だけをここで差し替えています）
        model.addAttribute("lanes", List.of(
                new BoardLane("受付", "lane--received", board.received()),
                new BoardLane("調理中", "lane--cooking", board.cooking()),
                new BoardLane("提供待ち", "lane--ready", board.ready())));
        // 経過時間を「出すか／赤くするか」の判断役。しきい値の比較を画面に書かず、
        // ここで組み立てた小さな判断役に聞く形にしている（ElapsedDisplay の説明を参照）。
        model.addAttribute("elapsedDisplay",
                new ElapsedDisplay(demoMode, LATE_MINUTES, DEMO_STALE_MINUTES));
        // 卓名 → その卓のチケット枚数。テンプレートでは ${sameTableCount[order.tableName]} で引く
        model.addAttribute("sameTableCount", countByTableName(board));
        // レイアウト（layout/staff.html）のナビで、いまいる場所に色を付けるための目印
        return "kitchen/board";
    }

    /**
     * 注文の状態を次へ進める。
     *
     * <p>{@code status} には {@link OrderStatus} の名前（COOKING / READY / COMPLETED …）が
     * 文字列で届きます。{@code OrderStatus.valueOf(...)} で enum に戻しますが、
     * URL を直接叩かれて存在しない名前が来ると {@link IllegalArgumentException} が飛ぶので、
     * ここで受け止めます。
     *
     * <p><b>この口ではキャンセルを行いません（CANCELED は受け付けない）</b><br>
     * キャンセルには専用の {@link #cancel} があり、画面（board.html）も
     * この口へ CANCELED を送りません。つまり<b>正規の利用者は実スタッフを含め誰も
     * ここに CANCELED を送らない</b>ので、弾いても現場の操作は 1 つも変わりません。
     * にもかかわらず受け付けたままにしていると、
     * 「同じ操作に URL が 2 本ある」状態になり、片方だけに掛けた制限
     * （公開デモで見学者にキャンセルを許さない、など）が
     * もう片方から素通りしてしまいます。実際その抜け道がありました（2026-08-24）。
     *
     * <p>これは<b>認可の話ではなく入力の妥当性の話</b>として書いています。
     * 「誰なら許すか」で書くと、ロールが増えるたびに条件を足して回ることになりますが、
     * 「この口はその操作をしない」で閉じておけば、あとから誰が来ても崩れません。
     *
     * <p><b>なぜ例外をここで catch するのか</b><br>
     * 状態遷移の違反（例：すでに提供済みの注文をもう一度進めようとした）は
     * {@link IllegalStateException} になります。これを素通しすると
     * {@code GlobalExceptionHandler} がエラーページを表示し、
     * 厨房ボードから離脱してしまいます。忙しい現場では致命的に使いづらいので、
     * この画面に限ってはメッセージだけ出してボードに戻します。
     *
     * <p><b>{@code @AuthenticationPrincipal}</b><br>
     * ログイン中のユーザー（{@link StaffUserDetails}）を引数として受け取れる指定です。
     * セキュリティ設定で {@code /kitchen/**} はログイン必須なので基本 null になりませんが、
     * 念のため {@link #staffNameOf(StaffUserDetails)} で null 安全に扱います。
     */
    @PostMapping("/orders/{id}/status")
    public String changeStatus(@PathVariable Long id,
                               @RequestParam String status,
                               @AuthenticationPrincipal StaffUserDetails user,
                               RedirectAttributes redirectAttributes) {
        try {
            OrderStatus next = OrderStatus.valueOf(status);
            // キャンセルは専用の /cancel が受け持つ。この口では扱わない（上の説明を参照）。
            //
            // わざと例外にして、すぐ下の catch へ落としています。
            // 「存在しない状態名が来た」も「この口では受け付けない状態名が来た」も、
            // 利用者から見れば同じ「その状態には変更できません」でしかありません。
            // 応答の形をここで作り分けると、同じ意味の画面表示が 2 通りに増えてしまいます。
            if (next == OrderStatus.CANCELED) {
                throw new IllegalArgumentException(
                        "キャンセルはこの操作では行えません: " + status);
            }
            Order order = orderService.changeStatus(id, next, staffNameOf(user));
            // 操作した本人が「どの卓を進めたか」をすぐ確かめられるよう、卓名を先頭に出す。
            // 番号だけだと、卓が 10 も 20 もある店では結局伝票を探し直すことになります。
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "%s（#%d）を「%s」にしました"
                            .formatted(order.getTableName(), order.getOrderNumber(), boardLabelOf(next)));

        } catch (IllegalArgumentException e) {
            // valueOf の失敗（存在しない状態名）と、この口では受け付けない CANCELED。
            // どちらも画面にボタンが無いので、通常の操作では起こらない。
            log.warn("この画面では扱えない状態が指定されました: {}", status);
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("その状態には変更できません（%s）".formatted(status)));

        } catch (IllegalStateException e) {
            // 状態遷移の違反。二人が同時に同じ伝票を触ったときなどに起こる。
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (OrderService.OrderNotFoundException e) {
            // 伝票が消えている（日付が変わった直後など）。これもボードに留まって知らせる。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/kitchen";
    }

    /**
     * 店側から注文をキャンセルする（材料切れ・お客さまからの取り消しの申し出など）。
     *
     * <p>キャンセルされた注文は伝票の請求対象から外れます。
     * 金額の計算し直しは {@code OrderService} が伝票側へ依頼するので、
     * ここで金額に触れることは一切ありません。
     *
     * <p>{@code required = false} を付けた引数は、フォームから送られてこなくても
     * エラーにならず null になります。
     */
    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            Order order = orderService.cancelByStaff(id, cancelReasonOf(reason), staffNameOf(user));
            // キャンセルした品は伝票の請求からも外れます（金額の再計算は OrderService 側で実行済み）。
            redirectAttributes.addFlashAttribute("flashInfo",
                    "%s（#%d）をキャンセルしました。お会計からも取り除かれます"
                            .formatted(order.getTableName(), order.getOrderNumber()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (OrderService.OrderNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/kitchen";
    }

    // ========================================================================
    //  品切れ管理
    // ========================================================================

    /**
     * 品切れ管理パネルの「カテゴリ 1 つぶん」。
     *
     * <p><b>なぜ Map ではなく record にしたか</b><br>
     * 以前は {@code Map<カテゴリ名, 商品リスト>} を画面に渡していました。
     * ところが 2026-08-26 にカテゴリへ飛ぶアンカーリンク（チップ）を足すにあたり、
     * <b>カテゴリの id</b> が必要になりました。キーが名前しか持たない Map では
     * id を運べません。「1 つの塊が持つ情報が 2 つ以上になったら record」が目安です。
     *
     * <p>record は「値を運ぶだけの入れ物」を 1 行で書ける Java 16 以降の書き方です。
     * getter は自動で作られ、名前は {@code getId()} ではなく {@code id()} になります。
     * そのため Thymeleaf 側も {@code ${g.id()}} と括弧付きで呼びます
     * （普通のクラスの {@code getXxx()} だけが {@code ${obj.xxx}} と省略できます）。
     *
     * @param id    カテゴリの id。アンカーの id（{@code #cat-3}）に使う
     * @param name  カテゴリ名。見出しとチップの文字に使う
     * @param items そのカテゴリの掲載中の商品（並び順は商品の sortOrder のまま）
     */
    public record StockCategory(Long id, String name, List<MenuItem> items) {
    }

    /**
     * 品切れ管理パネル。
     *
     * <p>カテゴリごとに見出しを付けて並べたいので、
     * カテゴリ単位の塊（{@link StockCategory}）に組み替えてから画面に渡します。
     *
     * <p>{@link LinkedHashMap} を使うのは<b>入れた順番が保たれる</b>ためです。
     * 普通の {@code HashMap} は順不同なので、並び順に意味がある画面では使えません。
     * （{@code itemsForSoldOutPanel()} は「カテゴリ順 → 商品の並び順」で返ってくるので、
     * その順に詰めれば見出しの順序もそのまま正しくなります）
     */
    @GetMapping("/stock")
    public String stock(Model model) {
        List<MenuItem> items = menuService.itemsForSoldOutPanel();

        // カテゴリ id で束ねる。名前で束ねると、同じ名前のカテゴリを 2 つ作られたときに
        // 別物どうしが 1 つの見出しに混ざってしまう（id なら絶対にぶつからない）。
        Map<Long, List<MenuItem>> itemsByCategoryId = new LinkedHashMap<>();
        Map<Long, String> categoryNames = new HashMap<>();
        for (MenuItem item : items) {
            // category は EntityGraph で一緒に読み込み済みなので、ここで触っても
            // LazyInitializationException にはならない（open-in-view: false のため要注意な箇所）
            Category category = item.getCategory();
            itemsByCategoryId.computeIfAbsent(category.getId(), key -> new ArrayList<>()).add(item);
            categoryNames.putIfAbsent(category.getId(), category.getName());
        }

        List<StockCategory> categoryGroups = new ArrayList<>();
        itemsByCategoryId.forEach((categoryId, list) ->
                categoryGroups.add(new StockCategory(categoryId, categoryNames.get(categoryId), list)));

        long soldOutCount = items.stream().filter(MenuItem::isSoldOut).count();

        model.addAttribute("categoryGroups", categoryGroups);
        model.addAttribute("itemCount", items.size());
        model.addAttribute("soldOutCount", soldOutCount);
        return "kitchen/stock";
    }

    /**
     * 残数（本日の数）を設定する。<b>欄を空にして送ると、数を数えない品に戻します。</b>
     *
     * <p>数量限定の品に「今日は 8 皿」と入れておくと、
     * 注文のたびに自動で減り、0 になった瞬間から各卓のメニューで売り切れ表示になります。
     * 減らす処理は条件付き UPDATE（{@code MenuService#tryConsumeStock}）なので、
     * 2 卓が同時に最後の 1 皿を頼んでも売り越えません。
     *
     * <p><b>「解除」の口をこちらへ寄せました（2026-08-27）。</b><br>
     * もとは {@code POST /stock/{itemId}/stock/clear} という別のボタンがありましたが、
     * 中身は同じ {@code setStock(id, null)} でした。行あたり 56px を使い、
     * そのぶん右端の「操作」列（この画面の主役）を表示領域の外へ押し出していました。
     *
     * <p>空欄はもともとここへ届いていました。{@code <input type="number">} を空のまま送ると
     * {@code stockCount=} という空文字が飛び、Spring が {@code Integer} へ変換する際に
     * {@code null} になるためです。ところが下の書式に {@code %d} を使っていたので、
     * 「残数を null に設定しました」と出ていました。
     * 届いてはいたが、正しく答えていなかった、という状態です。
     */
    @PostMapping("/stock/{itemId}/stock")
    public String setStock(@PathVariable Long itemId,
                           @RequestParam(required = false) Integer stockCount,
                           RedirectAttributes redirectAttributes) {
        try {
            String name = menuService.setStock(itemId, stockCount);
            if (stockCount == null) {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "「%s」の残数管理を解除しました（無制限に戻ります）".formatted(name));
            } else {
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "「%s」の残数を %d に設定しました".formatted(name, stockCount));
            }
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/kitchen/stock";
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/kitchen/stock";
        }
        return redirectToCategoryOf(itemId);
    }

    /**
     * 品切れフラグを反転させる（ワンタップで「売り切れ／販売再開」）。
     *
     * <p>戻り先は品切れパネルです。厨房ボードに飛ばすと、
     * 続けて何品も操作したいときに毎回パネルを開き直すことになるためです。
     *
     * <p><b>成功したときは、操作した品のカテゴリまで戻します</b>（{@link #redirectToCategoryOf}）。
     * この画面は 14 カテゴリ 94 行あるので、素の {@code /kitchen/stock} に戻すと
     * 毎回いちばん上に着地し、「続けて何品も」ができません。
     * カテゴリのチップで飛んだ意味も、1 操作で消えてしまいます。
     */
    @PostMapping("/stock/{itemId}/toggle")
    public String toggleSoldOut(@PathVariable Long itemId, RedirectAttributes redirectAttributes) {
        try {
            boolean soldOut = menuService.toggleSoldOut(itemId);
            // 商品名まで出したいので、トグル後の状態をもう一度読み直して名前を取る。
            // （toggleSoldOut は「品切れになったか」の真偽値しか返さないため）
            String name = menuService.itemWithOptions(itemId).getName();

            if (soldOut) {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "「%s」を品切れにしました。各卓のメニューから注文できなくなります".formatted(name));
            } else {
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "「%s」の販売を再開しました".formatted(name));
            }
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/kitchen/stock";
        }
        return redirectToCategoryOf(itemId);
    }

    /**
     * 品切れパネルの、その商品が属するカテゴリまで戻す。
     *
     * <p><b>成功時だけ使います。</b>失敗したときは素の {@code /kitchen/stock} に戻して、
     * 画面上端のエラーを必ず読ませます。うまくいかなかったのに操作した場所へ戻すと、
     * なぜ変わらないのかが分からないまま同じ操作を繰り返すことになります。
     *
     * <p>成功時に上端の成功メッセージが読まれなくなりますが、
     * <b>操作した行そのものが「品切れ」の表示に変わる</b>ので、
     * 結果は押した場所で見えます。離れた場所の通知より、そちらのほうが速く伝わります。
     *
     * <p>カテゴリが引けなかったときは素の URL に落とします。
     * 戻り先を決めるためだけの読み直しで例外を投げて、
     * 成功した操作を失敗に見せてしまわないようにするためです。
     */
    private String redirectToCategoryOf(Long itemId) {
        try {
            Long categoryId = menuService.itemWithOptions(itemId).getCategory().getId();
            return "redirect:/kitchen/stock#cat-" + categoryId;
        } catch (RuntimeException e) {
            return "redirect:/kitchen/stock";
        }
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * ボードに出ている注文を卓名ごとに数える。
     *
     * <p>戻り値は {@code 卓名 → 件数} の {@link Map} です。
     * 画面では {@code ${sameTableCount[order.tableName]}} と書くと件数が取り出せます。
     *
     * <p><b>{@code merge} の読み方</b><br>
     * {@code counts.merge(key, 1L, Long::sum)} は
     * 「キーが無ければ 1 を入れる。あれば今の値と 1 を足した結果を入れる」という意味です。
     * {@code get} して null かどうか調べて…と書かなくて済むので、
     * 数え上げではよく使われる書き方です。
     *
     * <p>件数は {@code Long}（long のラッパー）で持ちます。
     * {@code merge} の第 3 引数に渡した {@code Long::sum} が Long を返すためで、
     * {@code Integer} にすると型が合わずコンパイルできません。
     *
     * @param board 厨房ボードの 3 レーン
     * @return 卓名ごとの件数。ボードに 1 件も無い卓は入っていない（＝画面では null になる）
     */
    private static Map<String, Long> countByTableName(KitchenBoard board) {
        // 3 レーンをまとめて 1 本のリストにしてから数える。
        // 「受付にも調理中にも同じ卓の注文がある」ケースを取りこぼさないためです。
        List<Order> all = new ArrayList<>();
        all.addAll(board.received());
        all.addAll(board.cooking());
        all.addAll(board.ready());

        Map<String, Long> counts = new HashMap<>();
        for (Order order : all) {
            // getTableName() は伝票や卓が取れなくても "―" を返してくれる（null 安全）ので、
            // ここで null チェックを書く必要はありません。
            counts.merge(order.getTableName(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 状態の名前を、この厨房ボードの言い方に直す。
     *
     * <p>{@link OrderStatus} が持っている {@code staffLabel} は
     * 「お渡し可」「受渡済」というテイクアウト時代の言葉のままです。
     * enum のラベルはお客さま画面や管理画面など他の担当も使っているので<b>書き換えず</b>、
     * 厨房ボードに出す文言だけをこのメソッドで置き換えます。
     *
     * <p>{@code switch} 式で全部の値を書いておくと、
     * あとで状態が増えたときに「ここも直してね」とコンパイラが教えてくれます。
     */
    private static String boardLabelOf(OrderStatus status) {
        return switch (status) {
            case RECEIVED -> "受付";
            case COOKING -> "調理中";
            case READY -> "提供待ち";
            case COMPLETED -> "提供済み";
            case CANCELED -> "キャンセル";
        };
    }

    /**
     * 操作したスタッフの表示名を取り出す（null 安全）。
     *
     * <p>取れなかった場合でも「誰が触ったか分からない」と記録が残るよう、
     * 空文字ではなく代替の文字列を返します。
     */
    private static String staffNameOf(StaffUserDetails user) {
        if (user == null) {
            return "スタッフ";
        }
        String displayName = user.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            return user.getUsername();
        }
        return displayName;
    }

    /**
     * 例外のメッセージを画面に出せる形にする。
     *
     * <p>{@code List.of(...)} は要素に null を渡すと {@code NullPointerException} になります。
     * 例外のメッセージは必ず入っている想定ですが、
     * 「エラーを表示しようとして別のエラーが出る」のが一番やっかいなので、
     * ここで受け止めて代替の文言に差し替えておきます。
     */
    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "その操作は行えませんでした";
        }
        return message;
    }

    /**
     * キャンセル理由を整える。
     *
     * <p>{@code Order.canceledReason} の列は 100 文字までなので、
     * 長すぎる値が来たら切り詰めます（DB でエラーになるのを防ぐ）。
     */
    private static String cancelReasonOf(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_CANCEL_REASON;
        }
        String trimmed = reason.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    /**
     * 画面に渡すレーン 1 本分のデータ。
     *
     * <p><b>record（レコード）とは</b><br>
     * 「値を持つだけの入れ物」を 1 行で書ける Java の記法です。
     * getter・equals・toString が自動で作られます。
     * ただし getter の名前は {@code getTitle()} ではなく {@code title()} なので、
     * Thymeleaf からは {@code ${lane.title()}} のように<b>括弧付きで</b>呼びます。
     *
     * @param title    レーンの見出し（受付／調理中／提供待ち）
     * @param modifier CSS の修飾クラス（lane--received など）
     * @param orders   そのレーンに並ぶ注文
     */
    public record BoardLane(String title, String modifier, List<Order> orders) {

        /** レーンに並ぶ件数。 */
        public int size() {
            return orders.size();
        }

        /** 1 件もないか（「注文はありません」を出す判定に使う）。 */
        public boolean isEmpty() {
            return orders.isEmpty();
        }
    }

    /**
     * チケットの「経過時間」の見せ方を決める、小さな判断役。
     *
     * <p><b>実店舗（{@code demoMode == false}）では一切ふるまいが変わりません。</b>
     * {@code shows} は常に true を返し、{@code late} は
     * これまでどおり {@code 経過 >= lateMinutes} だけで決まります。
     * 遅延アラートは<b>本番の機能</b>で、料理が止まっていることに気づくための
     * 最後の砦なので、デモの都合で鈍らせてはいけません。
     *
     * <p><b>公開デモ（{@code demoMode == true}）でだけ</b>、
     * {@code staleMinutes} を超えた注文の数字と赤枠を引っ込めます。
     * 理由は {@link KitchenController#DEMO_STALE_MINUTES} に書いてあります。
     *
     * <p><b>なぜテンプレートの条件式ではなくここに置くのか</b><br>
     * 「数字を出すか」と「赤枠にするか」は<b>連動していないと矛盾</b>します。
     * 数字を隠したのに枠だけ赤い、という組み合わせが一度でも出ると、
     * 見た人は理由の分からない赤を見ることになります。
     * 2 つの条件を画面の別々の場所に書くと、その連動は簡単に崩れます。
     * ここにまとめておけば {@code late} が {@code shows} を内側で呼ぶ形で、
     * <b>構造として</b>矛盾しないようにできます。素の JUnit でも固定できます。
     *
     * @param demoMode     公開デモか（{@code app.guest-login}）
     * @param lateMinutes  遅延として赤くする閾値（実店舗の機能）
     * @param staleMinutes デモで「もう実態と合わない」とみなす閾値
     */
    public record ElapsedDisplay(boolean demoMode, long lateMinutes, long staleMinutes) {

        /** 経過時間の数字を出してよいか。 */
        public boolean shows(Order order) {
            return shows(order.getElapsedMinutes());
        }

        /** 遅延（{@code is-late}／赤枠）として見せるか。 */
        public boolean late(Order order) {
            return late(order.getElapsedMinutes());
        }

        /** 分数だけを受け取る版。テストから時計に左右されずに呼べるように分けてある。 */
        public boolean shows(long elapsedMinutes) {
            return !demoMode || elapsedMinutes < staleMinutes;
        }

        /**
         * 分数だけを受け取る版。
         *
         * <p>{@code shows} を先に確かめているのが要点です。
         * 数字を出さないと決めた注文は、赤枠にもしません。
         */
        public boolean late(long elapsedMinutes) {
            return shows(elapsedMinutes) && elapsedMinutes >= lateMinutes;
        }
    }
}
