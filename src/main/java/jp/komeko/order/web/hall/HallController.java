package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ホール（フロア）担当者が使う「伝票一覧」と「お会計」の画面。
 *
 * <p><b>この画面がやること</b>
 * <ol>
 *   <li>いま何卓が入っていて、それぞれいくらになっているかを一覧で見せる</li>
 *   <li>空いている卓に「ご案内」して伝票を開く</li>
 *   <li>人数を直す（テーブルチャージが変わる）</li>
 *   <li><b>お会計＝伝票を締める</b>。この店の売上が確定する、いちばん大事な操作</li>
 *   <li>誤って会計してしまったときに取り消す（リカバリ）</li>
 * </ol>
 *
 * <p><b>金額は絶対にここで計算しない</b><br>
 * 小計・テーブルチャージ・深夜料金・ご請求額・内消費税の計算は
 * {@link TableSession#recalculate(LocalDateTime, boolean)} の 1 箇所だけにあります。
 * コントローラや画面で「小計＋チャージ」のような式を書くと、
 * 片方だけ直したときに<b>お客さまに請求する金額とレジの金額がズレます</b>。
 * ここでは {@code getTotalAmount()} などの getter を読むだけにしています。
 *
 * <p><b>コントローラの役割</b><br>
 * 「リクエストを受ける → サービスに仕事を頼む → 画面に渡す値を用意する」だけの
 * 交通整理係です。業務のルールは {@link TableService} 側に置いてあります。
 *
 * <p><b>PRG（Post → Redirect → Get）</b><br>
 * 更新系（POST）のあとは必ずリダイレクトします。そうしないと、
 * 処理後の画面でブラウザの再読み込みを押したときに同じ POST が再送され、
 * <b>二重に会計してしまう</b>おそれがあります。会計画面では特に致命的です。
 */
@Controller
@RequestMapping("/hall")
public class HallController {

    private static final Logger log = LoggerFactory.getLogger(HallController.class);

    /** 画面に出す時刻の書式（19:02 のような形）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 人数プルダウンに並べる最大値。実店舗の座敷でも足りる程度に取っておく。 */
    private static final int MAX_GUEST_CHOICE = 20;

    /**
     * ご案内フォームの人数の初期値。
     *
     * <p>卓の席数（capacity）を初期値にすると、2 名で 4 人掛けに座ったときに
     * テーブルチャージを 2 名分多く頂いてしまいます。
     * 金額は「多すぎる」より「少なすぎる」ほうがまだ取り返しがつくので、
     * もっとも多い組数である 2 名を初期値にしています。
     * 間違えてもすぐ気づけるよう、ご案内後のメッセージに人数を出しています。
     */
    private static final int DEFAULT_GUEST_COUNT = 2;

    /** 会計メモの最大文字数（{@code TableSession.note} の桁数に合わせる）。 */
    private static final int NOTE_MAX_LENGTH = 200;

    private final TableService tableService;
    private final ShopSettingService shopSettingService;
    private final OrderService orderService;

    /**
     * コンストラクタインジェクション。
     *
     * <p>Spring が「このクラスを作るにはこれらが要る」と判断して自動で渡してくれます。
     * フィールドに {@code @Autowired} を付ける書き方より、
     * final にできてテストもしやすいので、こちらが推奨です。
     */
    public HallController(TableService tableService,
                          ShopSettingService shopSettingService,
                          OrderService orderService) {
        this.tableService = tableService;
        this.shopSettingService = shopSettingService;
        this.orderService = orderService;
    }

    // ========================================================================
    //  伝票一覧（ホールのホーム画面）
    // ========================================================================

    /**
     * 伝票一覧。
     *
     * <p>「いま開いている伝票」と「まだ誰も座っていない卓」を並べます。
     * 空席の判定は、<b>開いている伝票が指している卓の ID を集めて、
     * そこに入っていない卓が空席</b>という考え方です。
     * 卓側に「使用中フラグ」を持たせると、会計の取り消しなどで
     * フラグと伝票の状態がズレたときに直しようがなくなるため、
     * 常に伝票のほうから導き出しています（＝事実は 1 箇所にだけ持つ）。
     */
    @GetMapping
    public String board(Model model) {
        List<TableSession> bills = tableService.openSessions();
        List<DiningTable> tables = tableService.activeTables();

        // 開いている伝票が使っている卓の ID を集める。
        // Set（集合）にすると contains の判定が速く、重複も自動で消えます。
        Set<Long> occupiedTableIds = new HashSet<>();
        for (TableSession bill : bills) {
            occupiedTableIds.add(bill.getDiningTable().getId());
        }

        List<DiningTable> vacantTables = new ArrayList<>();
        for (DiningTable table : tables) {
            if (!occupiedTableIds.contains(table.getId())) {
                vacantTables.add(table);
            }
        }

        // 在席のお客さまの合計人数と、まだ厨房に残っている注文の件数。
        // 件数の数え方は TableSession#hasPendingOrders() と同じ条件（受付・調理中）に
        // そろえてあります。片方だけ READY を含めると、カードのバッジと
        // 上の集計が食い違って「どっちが本当？」となるためです。
        int guestTotal = 0;
        int pendingCount = 0;
        for (TableSession bill : bills) {
            guestTotal += bill.getGuestCount();
            for (Order order : bill.getOrders()) {
                if (order.getStatus().isActive()) {
                    pendingCount++;
                }
            }
        }

        model.addAttribute("bills", bills);
        model.addAttribute("vacantTables", vacantTables);
        model.addAttribute("occupiedCount", bills.size());
        model.addAttribute("vacantCount", vacantTables.size());
        model.addAttribute("guestTotal", guestTotal);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("guestOptions", guestOptions(MAX_GUEST_CHOICE));
        model.addAttribute("defaultGuestCount", DEFAULT_GUEST_COUNT);
        model.addAttribute("closedBills", closedBillsOfToday());
        // レイアウト（layout/staff.html）のナビで、いまいる場所に色を付けるための目印
        return "hall/board";
    }

    // ========================================================================
    //  伝票の詳細・お会計
    // ========================================================================

    /**
     * 伝票の詳細＋会計画面。
     *
     * <p><b>モデル名を "bill" にしている理由</b><br>
     * Thymeleaf では {@code ${session}} が HttpSession を指す予約語のため、
     * 伝票を "session" という名前でモデルに入れると衝突して正しく表示できません。
     * お客さま側の画面（{@code customer/bill.html}）と同じく "bill" に統一しています。
     */
    @GetMapping("/bills/{id}")
    public String bill(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        TableSession bill;
        try {
            bill = tableService.getSession(id);
        } catch (TableService.SessionNotFoundException e) {
            // 伝票が消えている（URL が古いなど）。エラーページに飛ばさず一覧に戻す。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }

        if (bill.isOpen()) {
            // 深夜料金は時刻で決まるので、23 時をまたぐと金額が変わります。
            // 画面を開いた「いま」の金額を出すために計算し直します。
            //
            // ※ ここで受け取っている伝票は DB との接続が切れた状態（detached）なので、
            //    この計算結果が DB に書き戻されることはありません。表示専用です。
            //    実際に請求する金額は closeSession() の中でもう一度計算されるので、
            //    表示と請求がズレる心配はありません。
            tableService.refresh(bill);
        }

        // 画面が使う店舗設定（${shop}）は GlobalModelAttributes が入れてくれるので、
        // ここで取り出す必要はありません。
        // 以前は深夜料金チェックの初期値を shop.isLateNight(now) で決めていたため
        // ここで読んでいましたが、その判定をやめたので不要になりました。
        model.addAttribute("bill", bill);
        // キャンセル済みの注文は請求から外れるので、明細も billable のものだけ出す
        model.addAttribute("orders", bill.getBillableOrders());
        model.addAttribute("openedAtLabel", bill.getOpenedAt().format(TIME_FORMAT));
        model.addAttribute("closedAtLabel",
                bill.getClosedAt() == null ? null : bill.getClosedAt().format(TIME_FORMAT));
        // 深夜料金チェックボックスの初期状態。
        //
        // 「いま深夜帯か（shop.isLateNight(now)）」で決めてはいけません。
        // 深夜料金は注文時刻ごとに決まるので、
        //   ・23:30 に注文があった卓を、5:30（深夜帯の外）に会計する
        //   ・22:00 で注文が終わった卓を、23:30（深夜帯の中）に会計する
        // のどちらも起こります。前者はチェックが外れて取りっぱぐれ、
        // 後者は対象が無いのにチェックが入って紛らわしい、となります。
        //
        // 開いている伝票は表示のたびにルールで計算し直されているので、
        // その結果をそのまま初期状態にします。
        // ただし一度スタッフが免除した伝票は、開け直しても外れたままにします
        // （人の判断を、計算結果で上書きしない）。
        model.addAttribute("lateNightDefault",
                !bill.isLateNightWaived() && bill.isLateNightApplied());
        // 確認ダイアログ用の「深夜料金を付けた場合のご請求額」。
        //
        // ふつうは recalculate 済みの getTotalWithLateNight() でよいが、
        // 免除フラグが立った伝票は再計算が NONE に強制されるため
        // lateNightAmount が常に 0 で、「付けた場合」がどこにも計算されていない。
        // そのままだと、チェックを入れ直して締めるとき、ダイアログが
        // 割増抜きの金額を「深夜料金 込み」と読み上げてしまい、
        // 実際に締まった金額のほうが高くなる。
        //
        // ここで受け取っている伝票は detached（表示専用。上のコメント参照）なので、
        // 免除を一時的に外して計算しても DB には書き戻らない。
        int totalIfLateNightApplied = bill.getTotalWithLateNight();
        if (bill.isOpen() && bill.isLateNightWaived()) {
            ShopSetting current = shopSettingService.currentReadOnly();
            bill.setLateNightWaived(false);
            bill.recalculate(current::isLateNight);
            totalIfLateNightApplied = bill.getTotalWithLateNight();
            // 画面本体の表示は免除状態のままにしたいので、元に戻して計算し直す
            bill.setLateNightWaived(true);
            bill.recalculate(current::isLateNight);
        }
        model.addAttribute("totalIfLateNightApplied", totalIfLateNightApplied);
        // いまの人数が選択肢に無いと「変更したら人数が減った」という事故になるので、
        // 現在値より小さい範囲で切らないようにしておく
        model.addAttribute("guestOptions",
                guestOptions(Math.max(MAX_GUEST_CHOICE, bill.getGuestCount())));
        return "hall/bill";
    }

    /**
     * 人数を変更する。
     *
     * <p>テーブルチャージは「単価 × 人数」なので、人数を直すとご請求額も変わります。
     * 再計算は {@link TableService#changeGuestCount(Long, int)} の中で行われます。
     */
    @PostMapping("/bills/{id}/guests")
    public String changeGuests(@PathVariable Long id,
                               @RequestParam(defaultValue = "1") int guestCount,
                               RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.changeGuestCount(id, guestCount);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "人数を %d 名に変更しました（テーブルチャージ ¥%,d）"
                            .formatted(bill.getGuestCount(), bill.getTableChargeAmount()));

        } catch (IllegalStateException e) {
            // 会計済みの伝票を直そうとした、など
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * 明細ごとの「深夜料金の対象にする／しない」を切り替える。
     *
     * <p>打ち直しの救済用です。詳しくは
     * {@code OrderService#setLateNightExempt} と {@code Order#lateNightExempt} を読んでください。
     *
     * <p>チェックボックスを押した瞬間に送信されるので、
     * {@code exempt} には切り替え<b>後</b>の状態が入ります。
     * チェックが外れた状態で送信されるとパラメータ自体が来ないため、
     * {@code defaultValue} で false を受け取ります（会計の締めと同じ理由）。
     */
    @PostMapping("/bills/{id}/orders/{orderId}/late-night")
    public String toggleOrderLateNight(@PathVariable Long id,
                                       @PathVariable Long orderId,
                                       @RequestParam(defaultValue = "false") boolean exempt,
                                       @AuthenticationPrincipal StaffUserDetails user,
                                       RedirectAttributes redirectAttributes) {
        try {
            orderService.setLateNightExempt(orderId, exempt, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    exempt ? "この注文を深夜料金の対象外にしました"
                           : "この注文を深夜料金の対象に戻しました");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * お会計（伝票を締める）。この店の売上が確定する操作です。
     *
     * <p><b>{@code applyLateNight} が boolean なのに defaultValue が要る理由</b><br>
     * HTML のチェックボックスは、チェックが外れていると<b>そもそも送信されません</b>。
     * 既定値を指定しておかないと「パラメータが無い」でエラーになるため、
     * 送られてこなかった＝チェックを外した＝false として受け取ります。
     *
     * <p>締めたあとは伝票の詳細に戻します。一覧（{@code /hall}）は開いている伝票しか
     * 並ばないので、そちらへ戻すと<b>直前に締めた伝票にたどり着けなくなり</b>、
     * 誤会計に気づいたときの取り消しができなくなるためです。
     */
    @PostMapping("/bills/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(defaultValue = "false") boolean applyLateNight,
                        @RequestParam(required = false) String note,
                        @AuthenticationPrincipal StaffUserDetails user,
                        RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.closeSession(id, applyLateNight, staffNameOf(user), noteOf(note));
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」のお会計を締めました。ご請求額 ¥%,d（内消費税 ¥%,d）"
                            .formatted(bill.getDiningTable().getName(),
                                    bill.getTotalAmount(), bill.getTaxAmount()));

        } catch (IllegalStateException e) {
            // すでに会計済み。二人が同時に締めようとしたときなどに起こる。
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of(messageOf(e), "画面が更新されました。最新の状態をご確認ください"));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    /**
     * 会計を取り消して伝票を開け直す（誤会計のリカバリ）。
     *
     * <p>締めたあとで「まだ追加注文があった」「別の卓と間違えた」と分かることがあります。
     * 売上に直結する操作なので、誰が取り消したかをログに残します
     * （記録は {@link TableService#reopenSession(Long, String)} の中）。
     */
    @PostMapping("/bills/{id}/reopen")
    public String reopen(@PathVariable Long id,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.reopenSession(id, staffNameOf(user));
            redirectAttributes.addFlashAttribute("flashInfo",
                    "「%s」の会計を取り消し、伝票を開き直しました".formatted(bill.getDiningTable().getName()));

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.SessionNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
            return "redirect:/hall";
        }
        return "redirect:/hall/bills/" + id;
    }

    // ========================================================================
    //  ご案内（空席に伝票を開く）
    // ========================================================================

    /**
     * スタッフが席にご案内する（伝票を開く）。
     *
     * <p>お客さまが QR を読んだ時点でも伝票は自動で開きますが、
     * 「先に席へ通してから、あとでゆっくり注文する」流れが普通なので、
     * ホール側からも開けるようにしています。
     * すでに開いていれば {@link TableService#openSession(Long, int)} が
     * 既存の伝票を返してくれるので、二重に伝票ができることはありません。
     */
    @PostMapping("/tables/{tableId}/open")
    public String openTable(@PathVariable Long tableId,
                            @RequestParam(defaultValue = "1") int guestCount,
                            RedirectAttributes redirectAttributes) {
        try {
            TableSession bill = tableService.openSession(tableId, guestCount);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "「%s」に %d 名さまをご案内しました".formatted(
                            bill.getDiningTable().getName(), bill.getGuestCount()));
            log.info("ホールからご案内: 卓={} 人数={}",
                    bill.getDiningTable().getName(), bill.getGuestCount());

        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));

        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(messageOf(e)));
        }
        return "redirect:/hall";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 本日（営業日）の会計済み伝票を、画面に出せる形にして返す。
     *
     * <p><b>なぜ「値のコピー」に詰め替えるのか</b><br>
     * 一覧取得に使っている問い合わせは卓（diningTable）しか一緒に読み込みません。
     * このアプリは {@code open-in-view: false} なので、
     * テンプレートの中から {@code bill.orders} のような未読み込みの関連を触ると
     * {@code LazyInitializationException} で画面が真っ白になります。
     * 画面に出す値だけを取り出した record にしておけば、
     * <b>あとから誰かがテンプレートに 1 行足しても壊れません</b>。
     */
    private List<ClosedBillRow> closedBillsOfToday() {
        LocalDate businessDate = shopSettingService.currentBusinessDate();
        List<ClosedBillRow> rows = new ArrayList<>();
        for (TableSession bill : tableService.sessionsOf(businessDate)) {
            if (bill.getStatus() != SessionStatus.CLOSED) {
                continue;   // まだ開いている伝票は上の一覧に出ているので、ここでは省く
            }
            rows.add(new ClosedBillRow(
                    bill.getId(),
                    bill.getDiningTable().getName(),
                    bill.getGuestCount(),
                    bill.getTotalAmount(),
                    bill.getOpenedAt().format(TIME_FORMAT),
                    bill.getClosedAt() == null ? "―" : bill.getClosedAt().format(TIME_FORMAT),
                    (bill.getClosedBy() == null || bill.getClosedBy().isBlank()) ? "―" : bill.getClosedBy()));
        }
        return rows;
    }

    /**
     * 人数プルダウンの選択肢（1 〜 upTo）を作る。
     *
     * <p>テンプレート側でループの数字を作る方法もありますが、
     * 「いくつまで出すか」という決めごとは Java 側にまとめたほうが変更しやすいので
     * ここで用意しています。
     */
    private static List<Integer> guestOptions(int upTo) {
        List<Integer> options = new ArrayList<>();
        for (int n = 1; n <= upTo; n++) {
            options.add(n);
        }
        return options;
    }

    /**
     * 操作したスタッフの表示名を取り出す（null 安全）。
     *
     * <p>会計は「誰が締めたか」を必ず残したいので、
     * 取れなかった場合も空文字ではなく代わりの文字列を返します。
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
     * 会計メモを整える。
     *
     * <p>{@code TableSession.note} の列は 200 文字までなので、
     * 長すぎる値が来たら切り詰めます（DB でエラーになるのを防ぐ）。
     * 空欄のときは null にして「メモなし」と区別できるようにします。
     */
    private static String noteOf(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > NOTE_MAX_LENGTH ? trimmed.substring(0, NOTE_MAX_LENGTH) : trimmed;
    }

    /**
     * 例外のメッセージを画面に出せる形にする。
     *
     * <p>{@code List.of(...)} は要素に null を渡すと {@code NullPointerException} になります。
     * 「エラーを表示しようとして別のエラーが出る」のが一番やっかいなので、
     * ここで受け止めて代わりの文言に差し替えます。
     */
    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "その操作は行えませんでした";
        }
        return message;
    }

    /**
     * 会計済み伝票 1 行分の表示用データ。
     *
     * <p><b>record（レコード）とは</b><br>
     * 「値を持つだけの入れ物」を 1 行で書ける Java の記法です。
     * getter・equals・toString が自動で作られます。
     * ただし getter の名前は {@code getTableName()} ではなく {@code tableName()} なので、
     * Thymeleaf からは {@code ${row.tableName()}} のように<b>括弧付きで</b>呼びます。
     *
     * @param id             伝票 ID（詳細画面へのリンクに使う）
     * @param tableName      卓名
     * @param guestCount     人数
     * @param totalAmount    ご請求額（円・税込）
     * @param openedAtLabel  入店時刻（HH:mm）
     * @param closedAtLabel  会計時刻（HH:mm）
     * @param closedBy       会計したスタッフ名
     */
    public record ClosedBillRow(Long id,
                                String tableName,
                                int guestCount,
                                int totalAmount,
                                String openedAtLabel,
                                String closedAtLabel,
                                String closedBy) {
    }
}
