package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.CartLine;
import jp.komeko.order.cart.CartOption;
import jp.komeko.order.domain.*;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.service.dto.KitchenBoard;
import jp.komeko.order.service.dto.OrderEvent;
import jp.komeko.order.service.dto.WaitEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 注文のユースケースをまとめたサービス。このアプリの心臓部。
 *
 * <p><b>なぜコントローラに書かず Service に集めるのか</b><br>
 * 「注文を受け付ける」という業務は、画面が Web でもスマホアプリでも同じはずです。
 * 画面まわりの都合（HTTP・HTML）と業務ルールを分けておくと、
 * テストが書きやすく、あとから API を足すのもラクになります。
 *
 * <p><b>{@code @Transactional} とは</b><br>
 * メソッドの最初から最後までを 1 つの「まとまった処理」として扱う指定です。
 * 途中で例外が出たら、それまでの DB 変更はすべて取り消されます（ロールバック）。
 * 注文と明細が中途半端に保存される、という事故を防いでくれます。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** まだ厨房で作業が残っている状態の集合。 */
    private static final List<OrderStatus> ACTIVE_STATUSES =
            List.of(OrderStatus.RECEIVED, OrderStatus.COOKING);

    /**
     * 営業日が変わったあとも、未提供の注文を厨房ボードに残しておく時間。
     *
     * <p><b>なぜ時間で線を引くのか</b><br>
     * 注文の営業日は伝票の値をコピーするので、5:00（営業日の切り替え）をまたいだ卓の
     * 注文は前営業日の日付を持ったまま残る。会計と調理は独立していて、4:50 に締めた卓に
     * 焼き待ちの注文が残ることもある。営業日や伝票の状態で絞ると、この注文が
     * ボードから消える＝厨房が焼くべき品を知り得なくなる
     * （詳細は {@code OrderRepository#findKitchenBoardOrders}）。
     * かといって「未提供なら永久に出す」にすると、数日前の焼き忘れや、
     * 締め忘れた伝票の注文がボードに積もり、<b>今夜の仕事が埋もれる</b>。
     * これはこれで別の事故なので、どこかで必ず切る必要がある。
     *
     * <p><b>なぜ 6 時間なのか</b><br>
     * 切り替えをまたいで厨房に残る仕事は、長く見積もっても数時間で片が付く
     * （4:50 の注文が 10:50 になっても未提供なら、それはもう焼かれていない）。
     * 一方で<b>次の営業（通常 17:00 開店）が始まるまでには必ず消えていてほしい</b>。
     * 切り替えが既定の 5:00 なら 6 時間後は 11:00 で、開店までに十分な余裕がある。
     * 12 時間まで延ばすと 17:00 に間に合わなくなるので、その手前に置いた。
     *
     * <p>いまの営業日の注文はこの窓に関係なく必ず出す（今夜の仕事を隠さないため）。
     * 営業日は 24 時間ぶんなので、こちらも無限には溜まらない。
     */
    private static final Duration CARRY_OVER_WINDOW = Duration.ofHours(6);

    private final OrderRepository orderRepository;
    private final OrderNumberService orderNumberService;
    private final ShopSettingService shopSettingService;
    private final CartService cartService;
    private final OrderEventPublisher eventPublisher;
    private final TableService tableService;
    private final MenuService menuService;

    public OrderService(OrderRepository orderRepository,
                        OrderNumberService orderNumberService,
                        ShopSettingService shopSettingService,
                        CartService cartService,
                        OrderEventPublisher eventPublisher,
                        TableService tableService,
                        MenuService menuService) {
        this.orderRepository = orderRepository;
        this.orderNumberService = orderNumberService;
        this.shopSettingService = shopSettingService;
        this.cartService = cartService;
        this.eventPublisher = eventPublisher;
        this.tableService = tableService;
        this.menuService = menuService;
    }

    // ========================================================================
    //  注文を受け付ける
    // ========================================================================

    /**
     * カートの中身を注文として確定し、卓の伝票に追加する。
     *
     * <p>処理の順番には理由があります。
     * <ol>
     *   <li>受付可能かを確認（営業時間・受付停止・伝票が開いているか）</li>
     *   <li>カートを最新のメニュー情報で洗い替え → 変化があれば一度お客さんに見せる</li>
     *   <li>注文番号を採番（別トランザクションで短くロック）</li>
     *   <li>注文と明細を保存し、伝票にぶら下げる</li>
     *   <li>伝票の合計を計算し直す</li>
     *   <li>厨房へ通知</li>
     * </ol>
     *
     * @param sessionId 追加先の伝票 ID（卓の QR から特定される）
     * @throws OrderRejectedException 受け付けられない理由があるとき
     */
    @Transactional
    public Order placeOrder(Cart cart, Long sessionId, String note) {
        return place(cart, sessionId, note).order();
    }

    /**
     * 注文の結果。
     *
     * @param order         できた注文
     * @param soldOutNotices 売り切れで落とした品の案内（空なら全部そのまま通った）
     */
    public record Placed(Order order, List<String> soldOutNotices) {
    }

    /**
     * {@link #placeOrder} と同じ処理で、<b>売り切れで落とした品も一緒に返す</b>。
     *
     * <p>お客さまの画面はこちらを使ってください。
     * 「◯◯は売り切れました。他の 3 品は承りました」と伝えるために、
     * 何が落ちたのかが要ります。
     *
     * <p>{@link #placeOrder} のほうを残してあるのは、
     * 注文を 1 件作りたいだけの呼び出し（テストや他のサービス）が多数あるためです。
     * そちらは案内を使わないので、戻り値を変えずに済ませています。
     */
    @Transactional
    public Placed place(Cart cart, Long sessionId, String note) {
        if (cart.isEmpty()) {
            throw new OrderRejectedException("カートに商品が入っていません");
        }

        ShopSetting setting = shopSettingService.current();
        LocalDateTime now = LocalDateTime.now();
        if (!setting.isOrderAcceptable(now)) {
            throw new OrderRejectedException(setting.orderRejectReason(now));
        }

        // お会計（closeSession）との同時実行を直列化する。
        // ロック無しだと、こちらが isOrderable() を確認したあとに会計が締まっても
        // そのままコミットでき、「締めた伝票に注文がぶら下がる」＝お客さまには
        // 承りましたと出たのに誰にも請求されない事故になる。
        // 先にロックを取れば、会計が先に締めた場合はここで CLOSED が見えて断れるし、
        // こちらが先ならお会計側が待ってから、この注文込みで締める。
        tableService.lockSession(sessionId);
        TableSession session = tableService.getSession(sessionId);
        if (!session.isOrderable()) {
            // 「締まっている」と「お会計待ち」を言い分ける。
            // どちらも注文は通せないが、お客さまにできることが違う。
            // 会計待ちは声をかければ再開してもらえるので、そう伝える。
            throw new OrderRejectedException(session.isClosing()
                    ? "ただいまお会計の準備中です。追加のご注文はスタッフにお声がけください"
                    : "このお席のお会計はすでに済んでいます。追加のご注文はスタッフにお声がけください");
        }

        // 値上げ・品切れが起きていないかを最終確認する。
        //
        // ★ 「落とす」と「止める」を分けている（2026-09-04）★
        //   もとは変化が 1 つでもあれば注文を丸ごと差し戻していた。
        //   だが売り切れは、他の卓が最後の 1 点を買っただけで起こる。
        //   誰も管理画面を触っていないのに、4 品のうち 1 品が売り切れただけで
        //   残り 3 品も通らない——というのが混雑時に起きていた。
        //
        //   売り切れ・取り扱い終了は「その品が無い」だけなので、落として先へ進む。
        //   価格やオプションの中身が変わったときは、これまでどおり止める。
        //   黙って通すと、お客さまが画面で見ていない金額で確定してしまうため。
        CartService.CartRefresh refreshed = cartService.refresh(cart);
        if (!refreshed.needsConfirm().isEmpty()) {
            List<String> messages = new ArrayList<>(refreshed.needsConfirm());
            messages.add("内容をご確認のうえ、もう一度ご注文ください");
            throw new OrderRejectedException(messages);
        }
        if (cart.isEmpty()) {
            throw new OrderRejectedException("ご注文の品がすべて売り切れました。申し訳ありません");
        }
        List<String> soldOutNotices = new ArrayList<>(refreshed.removed());

        // ── 残数を引く（数量限定の品の売り越え防止） ──
        // 条件付き UPDATE なので、2 卓が同時に最後の 1 皿を頼んでも片方だけが通る。
        // 途中の品で失敗したら OrderRejectedException を投げる
        // → このメソッドは @Transactional なので、それまでに引いた分は自動で巻き戻る。
        // 番号の採番（別トランザクションで即確定＝巻き戻らない）より前にやるのは、
        // 在庫切れで断るたびに番号が飛ぶのを避けるため。
        //
        // ここが「早い者勝ちで負けた」人の通り道。洗い替えの時点では買えたのに、
        // ボタンを押してから引くまでの間に、別の卓が最後の 1 点を持っていった場合。
        // 売り切れたその品だけ落として、残りは通す。
        //
        // ただし「品はあるが数が足りない」（残り 1 点なのに 2 個）は落とさない。
        // 何個にするかはお客さまが決めることなので、勝手に減らさず聞き返す。
        List<CartLine> accepted = new ArrayList<>();
        for (CartLine line : cart.getLines()) {
            if (menuService.tryConsumeStock(line.getMenuItemId(), line.getQuantity())) {
                accepted.add(line);
                continue;
            }
            Integer left = menuService.stockRemainingOf(line.getMenuItemId());
            if (left != null && left > 0) {
                throw new OrderRejectedException(
                        "「%s」は残り %d 点です。数量を変更してください"
                                .formatted(line.getMenuItemName(), left));
            }
            soldOutNotices.add("「%s」は売り切れました".formatted(line.getMenuItemName()));
        }
        if (accepted.isEmpty()) {
            throw new OrderRejectedException("ご注文の品がすべて売り切れました。申し訳ありません");
        }

        // 営業日と税率は「伝票（＝来店）」の値に合わせる。
        // 深夜 0 時をまたいでも同じ伝票のままにするため、いまの日付では計算しない。
        LocalDate businessDate = session.getBusinessDate();
        int orderNumber = orderNumberService.next(businessDate, setting.getOrderNumberStart());

        Order order = new Order(businessDate, orderNumber, session.getTaxRatePercent());
        order.setSession(session);
        order.setCustomerName(session.getDiningTable().getName());
        order.setNote(trimToNull(note, 200));

        // cart ではなく accepted を回す。売り切れで落とした品を入れないため
        for (CartLine line : accepted) {
            OrderLine orderLine = new OrderLine(
                    line.getMenuItemId(),
                    line.getMenuItemName(),
                    line.getBasePrice(),
                    line.getQuantity(),
                    line.getCookMinutes());
            for (CartOption option : line.getOptions()) {
                orderLine.addOption(new OrderLineOption(
                        option.choiceId(), option.groupName(), option.choiceName(), option.extraPrice()));
            }
            order.addLine(orderLine);
        }
        order.recalculate();

        Order saved = orderRepository.save(order);

        // 伝票の側にも追加しておく（双方向の関連は両側そろえるのが鉄則）。
        // そろえないと、このあとの合計計算で新しい注文が数えられない。
        session.getOrders().add(saved);
        tableService.refresh(session);

        log.info("注文受付 #{} 卓={} 合計{}円 {}点",
                saved.getOrderNumber(), session.getDiningTable().getName(),
                saved.getTotalAmount(), saved.getTotalQuantity());

        eventPublisher.publishOrderChanged(OrderEvent.created(saved.getId(), saved.getOrderNumber()));
        return new Placed(saved, List.copyOf(soldOutNotices));
    }

    // ========================================================================
    //  参照
    // ========================================================================

    /** お客さん専用 URL のトークンから注文を取得する。 */
    @Transactional(readOnly = true)
    public Optional<Order> findByToken(String token) {
        return orderRepository.findByPublicToken(token).map(this::hydrate);
    }

    @Transactional(readOnly = true)
    public Order getById(Long id) {
        return orderRepository.findWithLinesById(id)
                .map(this::hydrate)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** 厨房ボード（受付／調理中／お渡し可の 3 レーン）。 */
    @Transactional(readOnly = true)
    public KitchenBoard kitchenBoard() {
        LocalDate businessDate = shopSettingService.currentBusinessDate();
        // 未提供の注文は、営業日が前日でも伝票が会計済みでも出す（5:00 をまたいだ卓の
        // 注文がボードから消える事故を防ぐ。詳細は OrderRepository#findKitchenBoardOrders）。
        // ただし CARRY_OVER_WINDOW より古い焼き忘れは、今夜の仕事を埋めるので出さない
        List<Order> orders = orderRepository.findKitchenBoardOrders(
                businessDate,
                carryOverSince(),
                List.of(OrderStatus.RECEIVED, OrderStatus.COOKING, OrderStatus.READY));
        orders.forEach(this::hydrate);

        return new KitchenBoard(
                orders.stream().filter(o -> o.getStatus() == OrderStatus.RECEIVED).toList(),
                orders.stream().filter(o -> o.getStatus() == OrderStatus.COOKING).toList(),
                orders.stream().filter(o -> o.getStatus() == OrderStatus.READY).toList());
    }

    /**
     * まだ提供していない注文の件数（店舗ヘッダーの「未提供 N 件」）。
     *
     * <p>数えるのは {@link #kitchenBoard()} と<b>同じ母集合</b>——
     * 受付・調理中・お渡し可の 3 レーンに出ている注文です。
     * 厨房ボードの見出し「未処理 N 件」と必ず同じ数字になります。
     *
     * <p>ヘッダーは店側の全画面で描かれるので、
     * {@code kitchenBoard()} を呼んで {@code activeCount()} を読む形にはしません。
     * あちらは明細・伝票・卓まで読み込むので、数字 1 つには重すぎます。
     */
    @Transactional(readOnly = true)
    public int pendingCount() {
        return (int) orderRepository.countKitchenBoardOrders(
                shopSettingService.currentBusinessDate(),
                carryOverSince(),
                List.of(OrderStatus.RECEIVED, OrderStatus.COOKING, OrderStatus.READY));
    }

    /** 当日の全注文（管理画面の一覧）。 */
    @Transactional(readOnly = true)
    public List<Order> ordersOf(LocalDate businessDate) {
        List<Order> orders = orderRepository.findByBusinessDateOrderByOrderNumberDesc(businessDate);
        orders.forEach(this::hydrate);
        return orders;
    }

    /**
     * 待ち時間の目安を計算する。
     *
     * <p>考え方はシンプルです。
     * <pre>
     *   自分より前に並んでいる注文の調理時間の合計 ÷ 鉄板で同時に焼ける数
     *     ＋ 自分の注文の調理時間 ÷ 同時に焼ける数
     *     − すでに経過した時間
     * </pre>
     * 厳密な予測ではなく「体感に近い目安」を出すのが目的です。
     * 実際の待ち時間より少し長めに出すほうが、お客さんの満足度は上がります。
     *
     * <p><b>数える母集合は厨房ボードとそろえること。</b>
     * 以前はここだけ「注文自身の営業日と厳密一致」で数えていたため、
     * 5:00 をまたぐと、またぎ卓の注文と新しい営業日の注文が互いを
     * 「前にいる組」として数えませんでした。実際に鉄板を占領しているのは
     * ボードに出ている注文のほうなので、お客さまの「あと ◯ 組」だけが
     * 実態より少なく出る（＝待たされる）ことになります。
     */
    @Transactional(readOnly = true)
    public WaitEstimate estimateWait(Order order) {
        if (order.getStatus() == OrderStatus.READY) {
            return new WaitEstimate(0, 0);
        }
        if (order.getStatus().isClosed()) {
            return WaitEstimate.none();
        }

        ShopSetting setting = shopSettingService.currentReadOnly();
        int capacity = Math.max(1, setting.getGriddleCapacity());

        // 注文の営業日ではなく「いまの営業日＋持ち越しの窓」で数える（＝ボードと同じ母集合）。
        // 状態が RECEIVED/COOKING だけなのは意図的で、READY はもう鉄板を空けているため
        LocalDate businessDate = setting.businessDateOf(LocalDateTime.now());
        LocalDateTime since = carryOverSince();
        long ahead = orderRepository.countAheadOf(
                businessDate, since, ACTIVE_STATUSES, order.getCreatedAt());
        Long aheadMinutesRaw = orderRepository.sumCookMinutesAheadOf(
                businessDate, since, ACTIVE_STATUSES, order.getCreatedAt());
        long aheadMinutes = aheadMinutesRaw == null ? 0L : aheadMinutesRaw;

        long queueMinutes = ceilDiv(aheadMinutes, capacity);
        long ownMinutes = ceilDiv(order.getEstimatedCookMinutes(), capacity);
        long elapsed = order.getElapsedMinutes();

        long estimate = queueMinutes + ownMinutes - elapsed;
        // マイナスになったら「まもなく」扱い。上限も設けて非現実的な数字を出さない。
        int minutes = (int) Math.max(0, Math.min(estimate, 120));

        return new WaitEstimate(ahead, minutes);
    }

    /**
     * 営業日が違う未提供注文を拾う下限時刻（{@link #CARRY_OVER_WINDOW} 参照）。
     * 厨房ボードと待ち時間の目安が同じ母集合を見るよう、必ずここを通す。
     */
    private static LocalDateTime carryOverSince() {
        return LocalDateTime.now().minus(CARRY_OVER_WINDOW);
    }

    private static long ceilDiv(long value, long divisor) {
        if (divisor <= 0) {
            return value;
        }
        return (value + divisor - 1) / divisor;
    }

    // ========================================================================
    //  状態を進める
    // ========================================================================

    /**
     * 注文の状態を変更する（厨房画面から呼ばれる）。
     *
     * @param orderId   注文 ID
     * @param next      次の状態
     * @param staffName 操作したスタッフの表示名（記録用）
     * @throws IllegalStateException 許可されていない遷移のとき
     */
    @Transactional
    public Order changeStatus(Long orderId, OrderStatus next, String staffName) {
        lockBillOf(orderRepository.findSessionIdById(orderId));
        Order order = orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        boolean becomesCanceled = next == OrderStatus.CANCELED
                && order.getStatus() != OrderStatus.CANCELED;
        // 焼き上がり等の進行は会計後でも許す（厨房の実態）が、キャンセルは金額が動くので
        // 会計済みの伝票では拒否する（理由は requireBillStillOpenForCancel）
        if (becomesCanceled) {
            requireBillStillOpenForCancel(order);
        }
        order.changeStatus(next, staffName);
        hydrate(order);
        refreshSessionOf(order);
        // キャンセル経路はどこを通っても在庫を戻す（cancelByStaff / cancelByCustomer /
        // この汎用メソッド）。「取り消したのに残数が戻らない」は現場で必ず混乱を生む。
        //
        // ★ 在庫の復元は必ず「伝票の再計算のあと」に置くこと（詳細は restoreStockOf の説明）。
        if (becomesCanceled) {
            restoreStockOf(order);
        }

        log.info("注文 #{} → {} ({})", order.getOrderNumber(), next.getStaffLabel(), staffName);
        eventPublisher.publishOrderChanged(
                OrderEvent.statusChanged(order.getId(), order.getOrderNumber(), next.name()));
        return order;
    }

    /**
     * この注文を深夜料金の対象から外す／戻す（スタッフ操作）。
     *
     * <p>いちばんの用途は<b>打ち直しの救済</b>です。
     * 22:55 に受けた注文を 23:05 に誤って取り消し、23:06 に入れ直すと、
     * 注文時刻が新しくなるぶん 10% が乗ってしまいます。
     * お客さまから見れば同じ品を同じ時間に頼んだだけなので、これは説明がつきません。
     * その場合はスタッフがここで対象から外します。戻すこともできます。
     *
     * <p><b>注文時刻そのものは書き換えません。</b>
     * 「いつ厨房に入ったか」は提供時間の集計にも使う事実の記録です。
     * 事実は残したまま、判断（割増を取るか）だけを別の項目として持たせています。
     *
     * @param exempt true で対象外にする。false で通常どおり注文時刻で判定する
     */
    @Transactional
    public Order setLateNightExempt(Long orderId, boolean exempt, String staffName) {
        lockBillOf(orderRepository.findSessionIdById(orderId));
        Order order = orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        // 会計済みの伝票では金額の根拠を動かさない。
        // 画面はボタンを出していないが（bill.html）、古いタブや直接 POST からは
        // 届くので、サーバ側でも塞ぐ。ここを通すと、あとで開け直したときの
        // 再計算で確定済みの請求額が変わり、証跡が壊れる
        TableSession billOfOrder = order.getSession();
        if (billOfOrder != null && !billOfOrder.isActive()) {
            throw new IllegalStateException(
                    "会計済みの伝票では変更できません。先に会計を取り消して伝票を開け直してください");
        }
        if (order.isLateNightExempt() == exempt) {
            return order;   // 変化なし。伝票の再計算もログも要らない
        }
        order.setLateNightExempt(exempt);
        hydrate(order);
        // 計算し直さないと画面の合計が変わらない
        refreshSessionOf(order);

        log.info("注文 #{} の深夜料金を{}にしました（操作者={}）",
                order.getOrderNumber(), exempt ? "対象外" : "対象", staffName);
        return order;
    }

    /** 店側からのキャンセル（材料切れなど）。 */
    @Transactional
    public Order cancelByStaff(Long orderId, String reason, String staffName) {
        lockBillOf(orderRepository.findSessionIdById(orderId));
        Order order = orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        requireBillStillOpenForCancel(order);
        boolean alreadyCanceled = order.getStatus() == OrderStatus.CANCELED;
        order.cancel(reason, staffName);
        hydrate(order);
        refreshSessionOf(order);
        if (!alreadyCanceled) {
            restoreStockOf(order);
        }

        eventPublisher.publishOrderChanged(
                OrderEvent.statusChanged(order.getId(), order.getOrderNumber(), OrderStatus.CANCELED.name()));
        return order;
    }

    /**
     * お客さん自身によるキャンセル。
     * まだ焼き始めていない（RECEIVED の）ときだけ許可します。
     */
    @Transactional
    public Order cancelByCustomer(String token) {
        lockBillOf(orderRepository.findSessionIdByPublicToken(token));
        Order order = orderRepository.findByPublicToken(token)
                .orElseThrow(() -> new OrderNotFoundException(token));
        if (!order.isCustomerCancelable()) {
            throw new OrderRejectedException("すでに調理を開始しているためキャンセルできません。お手数ですが店頭スタッフへお声がけください");
        }
        // 会計済みの伝票は金額が確定している。ここからの取り消しは通さない
        // （スタッフ側と同じ理由。文言だけお客さま向けにしている）
        TableSession billOfOrder = order.getSession();
        if (billOfOrder != null && !billOfOrder.isActive()) {
            throw new OrderRejectedException(
                    "お会計がお済みのため、この画面からは取り消せません。お手数ですが店頭スタッフへお声がけください");
        }
        boolean alreadyCanceled = order.getStatus() == OrderStatus.CANCELED;
        order.cancel("お客様都合", "customer");
        hydrate(order);
        refreshSessionOf(order);
        if (!alreadyCanceled) {
            restoreStockOf(order);
        }

        eventPublisher.publishOrderChanged(
                OrderEvent.statusChanged(order.getId(), order.getOrderNumber(), OrderStatus.CANCELED.name()));
        return order;
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 注文を<b>読む前に</b>、その注文がぶら下がっている伝票の行ロックを取る。
     *
     * <p><b>なぜ注文ではなく「伝票」をロックするのか</b><br>
     * 注文の状態が変わると、必ず伝票の金額も変わります（{@link #refreshSessionOf}）。
     * つまり注文への操作は、実質「伝票を書き換える操作」です。
     * お会計・人数変更・追加注文も同じ伝票行を取り合うので、
     * <b>伝票の行を待ち合わせ場所にすると、その卓に関する操作がすべて 1 列に並びます</b>。
     * 注文ごとにロックしてもお会計とは直列化されず、意味がありません。
     *
     * <p><b>なぜ「読む前」なのか</b><br>
     * 先に注文を読むと、その古い写しが永続化コンテキストに残ります。
     * そのあとロックを取って読み直しても Hibernate は同じインスタンスを返すため、
     * 「もうキャンセル済みか」の判定が古い状態のまま行われ、
     * 在庫が二重に戻ったり、キャンセルが COOKING に書き戻されたりします。
     * だから伝票 ID だけを先に引き（{@code OrderRepository#findSessionIdById}）、
     * ロックを取ってから注文を読みます。
     *
     * <p><b>ロックの順番は「伝票 → menu_item」で固定すること。</b><br>
     * 在庫の増減（{@code MenuItemRepository}）は伝票より<b>あと</b>に来ます。
     * どこか 1 箇所でも逆順にすると、2 つの処理が互いの相手を待つ
     * デッドロック（AB-BA）になります。
     *
     * @param sessionId 伝票 ID。注文が見つからないときは空（このあと読む側が例外にする）
     */
    private void lockBillOf(Optional<Long> sessionId) {
        sessionId.ifPresent(tableService::lockSession);
    }

    /**
     * キャンセルは「開いている伝票」の注文にしか許さない。
     *
     * <p>会計済みの伝票は請求額が確定した証跡で、保存されている
     * 小計・ご請求額はもう再計算されない（{@code TableService#applyCurrentAmounts}
     * が閉じた伝票を読み飛ばすため）。その状態で注文だけキャンセルにすると、
     * 明細は「キャンセル済み」なのに合計は元のまま、という
     * 誰にも説明できない伝票ができあがる（2026-08-22 のレビューで発覚）。
     * 取り消したいときは、先に会計を取り消して伝票を開け直す。
     * それなら再計算が走り、金額と明細が必ず一致する。
     */
    private void requireBillStillOpenForCancel(Order order) {
        TableSession session = order.getSession();
        if (session != null && !session.isActive()) {
            throw new IllegalStateException(
                    "会計済みの伝票の注文は取り消せません。先に会計を取り消して伝票を開け直してください");
        }
    }

    /**
     * 明細のオプションまで読み込んでおく。
     *
     * <p>{@code open-in-view: false} のため、画面を描く時点では DB 接続がありません。
     * トランザクションの中で {@code size()} を呼んで実体化させておきます。
     * {@code OrderLine.options} には {@code @BatchSize} が付いているので、
     * 明細 20 件でも SQL は 1 回で済みます。
     */
    private Order hydrate(Order order) {
        for (OrderLine line : order.getLines()) {
            line.getOptions().size();
        }
        return order;
    }

    /**
     * キャンセルされた注文の分だけ、残数（在庫）を戻す。
     *
     * <p>呼び出し側で「初めてキャンセルになったときだけ」を保証すること。
     * 二重に戻すと、実際には無い在庫が画面に現れて売り越えの原因になる。
     * 在庫を管理していない商品や、すでに削除された商品は
     * {@code restoreStock} 側が素通りしてくれる。
     *
     * <p><b>★ 必ずトランザクション内の「最後の」処理として呼ぶこと。</b><br>
     * {@code restoreStock} はバルク UPDATE で、
     * {@code @Modifying(clearAutomatically = true)} が付いている。
     * 実行した瞬間に<b>永続化コンテキストの全エンティティがデタッチされる</b>ので、
     * このあとに {@code refreshSessionOf} のような遅延読み込みを挟むと
     * {@code LazyInitializationException} で全体がロールバックする。
     * 実際 2026-08-22 に、公開デモのキャンセルが全経路 HTTP 500 になっていた
     * （サービス直呼びのテストは {@code @Transactional} が永続化コンテキストを
     * 共有してしまうため検出できない。{@code KitchenCancelHttpTest} が
     * 本番と同じリクエスト境界でこの順序を固定している）。
     */
    private void restoreStockOf(Order order) {
        for (OrderLine line : order.getLines()) {
            if (line.getMenuItemId() != null) {
                menuService.restoreStock(line.getMenuItemId(), line.getQuantity());
            }
        }
    }

    /**
     * 注文の状態が変わったら、伝票の合計も計算し直す。
     *
     * <p>キャンセルされた注文は請求から外れるので、
     * ここを忘れると「取り消したのに金額が減らない」という
     * いちばん気づかれやすい不具合になります。
     */
    private void refreshSessionOf(Order order) {
        TableSession session = order.getSession();
        if (session != null && session.isActive()) {
            tableService.refresh(session);
        }
    }

    private static String trimToNull(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /** 注文が見つからないときの例外。 */
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(Object key) {
            super("注文が見つかりません（%s）".formatted(key));
        }
    }
}
