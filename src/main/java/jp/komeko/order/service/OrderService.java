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
        if (cart.isEmpty()) {
            throw new OrderRejectedException("カートに商品が入っていません");
        }

        ShopSetting setting = shopSettingService.current();
        LocalDateTime now = LocalDateTime.now();
        if (!setting.isOrderAcceptable(now)) {
            throw new OrderRejectedException(setting.orderRejectReason(now));
        }

        TableSession session = tableService.getSession(sessionId);
        if (!session.isOrderable()) {
            throw new OrderRejectedException(
                    "このお席のお会計はすでに済んでいます。追加のご注文はスタッフにお声がけください");
        }

        // 値上げ・品切れが起きていないかを最終確認する。
        // 変化があったら注文は通さず、更新後のカートを見せて再確認してもらう。
        List<String> changes = cartService.refresh(cart);
        if (!changes.isEmpty()) {
            List<String> messages = new ArrayList<>(changes);
            messages.add("内容をご確認のうえ、もう一度ご注文ください");
            throw new OrderRejectedException(messages);
        }
        if (cart.isEmpty()) {
            throw new OrderRejectedException("ご注文の品がすべて売り切れました。申し訳ありません");
        }

        // ── 残数を引く（数量限定の品の売り越え防止） ──
        // 条件付き UPDATE なので、2 卓が同時に最後の 1 皿を頼んでも片方だけが通る。
        // 途中の品で失敗したら OrderRejectedException を投げる
        // → このメソッドは @Transactional なので、それまでに引いた分は自動で巻き戻る。
        // 番号の採番（別トランザクションで即確定＝巻き戻らない）より前にやるのは、
        // 在庫切れで断るたびに番号が飛ぶのを避けるため。
        for (CartLine line : cart.getLines()) {
            if (!menuService.tryConsumeStock(line.getMenuItemId(), line.getQuantity())) {
                Integer left = menuService.stockRemainingOf(line.getMenuItemId());
                if (left != null && left > 0) {
                    throw new OrderRejectedException(
                            "「%s」は残り %d 点です。数量を変更してください"
                                    .formatted(line.getMenuItemName(), left));
                }
                throw new OrderRejectedException(
                        "「%s」は売り切れました".formatted(line.getMenuItemName()));
            }
        }

        // 営業日と税率は「伝票（＝来店）」の値に合わせる。
        // 深夜 0 時をまたいでも同じ伝票のままにするため、いまの日付では計算しない。
        LocalDate businessDate = session.getBusinessDate();
        int orderNumber = orderNumberService.next(businessDate, setting.getOrderNumberStart());

        Order order = new Order(businessDate, orderNumber, session.getTaxRatePercent());
        order.setSession(session);
        order.setCustomerName(session.getDiningTable().getName());
        order.setNote(trimToNull(note, 200));

        for (CartLine line : cart.getLines()) {
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
        return saved;
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
        List<Order> orders = orderRepository.findByBusinessDateAndStatusInOrderByCreatedAtAsc(
                businessDate,
                List.of(OrderStatus.RECEIVED, OrderStatus.COOKING, OrderStatus.READY));
        orders.forEach(this::hydrate);

        return new KitchenBoard(
                orders.stream().filter(o -> o.getStatus() == OrderStatus.RECEIVED).toList(),
                orders.stream().filter(o -> o.getStatus() == OrderStatus.COOKING).toList(),
                orders.stream().filter(o -> o.getStatus() == OrderStatus.READY).toList());
    }

    /** サイネージ用：調理中の番号一覧。 */
    @Transactional(readOnly = true)
    public List<Integer> cookingNumbers() {
        return numbersOf(OrderStatus.COOKING);
    }

    /** サイネージ用：お渡しできる番号一覧。 */
    @Transactional(readOnly = true)
    public List<Integer> readyNumbers() {
        return numbersOf(OrderStatus.READY);
    }

    private List<Integer> numbersOf(OrderStatus status) {
        LocalDate businessDate = shopSettingService.currentBusinessDate();
        return orderRepository.findByBusinessDateAndStatusOrderByOrderNumberAsc(businessDate, status)
                .stream()
                .map(Order::getOrderNumber)
                .toList();
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

        long ahead = orderRepository.countAheadOf(
                order.getBusinessDate(), ACTIVE_STATUSES, order.getCreatedAt());
        Long aheadMinutesRaw = orderRepository.sumCookMinutesAheadOf(
                order.getBusinessDate(), ACTIVE_STATUSES, order.getCreatedAt());
        long aheadMinutes = aheadMinutesRaw == null ? 0L : aheadMinutesRaw;

        long queueMinutes = ceilDiv(aheadMinutes, capacity);
        long ownMinutes = ceilDiv(order.getEstimatedCookMinutes(), capacity);
        long elapsed = order.getElapsedMinutes();

        long estimate = queueMinutes + ownMinutes - elapsed;
        // マイナスになったら「まもなく」扱い。上限も設けて非現実的な数字を出さない。
        int minutes = (int) Math.max(0, Math.min(estimate, 120));

        return new WaitEstimate(ahead, minutes);
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
        Order order = orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        boolean becomesCanceled = next == OrderStatus.CANCELED
                && order.getStatus() != OrderStatus.CANCELED;
        order.changeStatus(next, staffName);
        hydrate(order);
        // キャンセル経路はどこを通っても在庫を戻す（cancelByStaff / cancelByCustomer /
        // この汎用メソッド）。「取り消したのに残数が戻らない」は現場で必ず混乱を生む。
        if (becomesCanceled) {
            restoreStockOf(order);
        }
        refreshSessionOf(order);

        log.info("注文 #{} → {} ({})", order.getOrderNumber(), next.getStaffLabel(), staffName);
        eventPublisher.publishOrderChanged(
                OrderEvent.statusChanged(order.getId(), order.getOrderNumber(), next.name()));
        return order;
    }

    /** 店側からのキャンセル（材料切れなど）。 */
    @Transactional
    public Order cancelByStaff(Long orderId, String reason, String staffName) {
        Order order = orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        boolean alreadyCanceled = order.getStatus() == OrderStatus.CANCELED;
        order.cancel(reason, staffName);
        hydrate(order);
        if (!alreadyCanceled) {
            restoreStockOf(order);
        }
        refreshSessionOf(order);

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
        Order order = orderRepository.findByPublicToken(token)
                .orElseThrow(() -> new OrderNotFoundException(token));
        if (!order.isCustomerCancelable()) {
            throw new OrderRejectedException("すでに調理を開始しているためキャンセルできません。お手数ですが店頭スタッフへお声がけください");
        }
        boolean alreadyCanceled = order.getStatus() == OrderStatus.CANCELED;
        order.cancel("お客様都合", "customer");
        hydrate(order);
        if (!alreadyCanceled) {
            restoreStockOf(order);
        }
        refreshSessionOf(order);

        eventPublisher.publishOrderChanged(
                OrderEvent.statusChanged(order.getId(), order.getOrderNumber(), OrderStatus.CANCELED.name()));
        return order;
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

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
        if (session != null && session.isOpen()) {
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
