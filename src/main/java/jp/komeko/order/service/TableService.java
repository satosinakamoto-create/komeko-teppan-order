package jp.komeko.order.service;

import jp.komeko.order.domain.*;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 卓（テーブル）と伝票（来店）を扱うサービス。
 *
 * <p>イートインのモバイルオーダーの中心となる考え方は次の 2 つです。
 *
 * <ol>
 *   <li><b>卓は固定、伝票は来店ごと。</b>
 *       QR は卓に貼りっぱなしなので固定。
 *       伝票（{@link TableSession}）はお客さんが入れ替わるたびに新しく作る。</li>
 *   <li><b>「いま開いている伝票」は卓につき 1 つ。</b>
 *       QR を読んだら、その卓の開いている伝票を探して、無ければ作る。</li>
 * </ol>
 */
@Service
public class TableService {

    private static final Logger log = LoggerFactory.getLogger(TableService.class);

    private final DiningTableRepository tableRepository;
    private final TableSessionRepository sessionRepository;
    private final ShopSettingService shopSettingService;

    public TableService(DiningTableRepository tableRepository,
                        TableSessionRepository sessionRepository,
                        ShopSettingService shopSettingService) {
        this.tableRepository = tableRepository;
        this.sessionRepository = sessionRepository;
        this.shopSettingService = shopSettingService;
    }

    // ========================================================================
    //  卓
    // ========================================================================

    @Transactional(readOnly = true)
    public List<DiningTable> allTables() {
        return tableRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<DiningTable> activeTables() {
        return tableRepository.findByActiveTrueOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public DiningTable getById(Long id) {
        return tableRepository.findById(id).orElseThrow(() -> new TableNotFoundException(id));
    }

    /** QR のトークンから卓を引く。無効な卓なら例外。 */
    @Transactional(readOnly = true)
    public DiningTable getByAccessToken(String accessToken) {
        DiningTable table = tableRepository.findByAccessToken(accessToken)
                .orElseThrow(() -> new TableNotFoundException(accessToken));
        if (!table.isActive()) {
            throw new OrderRejectedException("この席はただいまご利用いただけません。スタッフにお声がけください");
        }
        return table;
    }

    @Transactional
    public DiningTable createTable(String name, int capacity, int sortOrder) {
        if (tableRepository.existsByName(name)) {
            throw new IllegalArgumentException("同じ名前の卓がすでにあります: " + name);
        }
        return tableRepository.save(new DiningTable(name, capacity, sortOrder));
    }

    @Transactional
    public void updateTable(Long id, String name, int capacity, int sortOrder, boolean active) {
        DiningTable table = getById(id);
        if (!table.getName().equals(name) && tableRepository.existsByName(name)) {
            throw new IllegalArgumentException("同じ名前の卓がすでにあります: " + name);
        }
        table.setName(name);
        table.setCapacity(capacity);
        table.setSortOrder(sortOrder);
        table.setActive(active);
    }

    /**
     * 卓を削除する。
     * 伝票が 1 件でも紐づいていると過去の会計記録が壊れるので、
     * その場合は削除させず「利用停止」を促します。
     */
    @Transactional
    public void deleteTable(Long id) {
        DiningTable table = getById(id);
        boolean used = sessionRepository.findAll().stream()
                .anyMatch(s -> s.getDiningTable() != null && id.equals(s.getDiningTable().getId()));
        if (used) {
            throw new IllegalStateException(
                    "「%s」には過去の伝票が残っているため削除できません。「利用停止」にしてください".formatted(table.getName()));
        }
        tableRepository.delete(table);
    }

    /** QR を作り直す（それまでに貼った QR は無効になる）。 */
    @Transactional
    public void regenerateToken(Long id) {
        DiningTable table = getById(id);
        table.regenerateAccessToken();
        log.warn("卓「{}」の QR を再発行しました。古い QR は使えなくなります", table.getName());
    }

    // ========================================================================
    //  伝票（来店）
    // ========================================================================

    /** その卓で開いている伝票を返す（無ければ空）。 */
    @Transactional(readOnly = true)
    public Optional<TableSession> currentSession(Long tableId) {
        return sessionRepository
                .findFirstByDiningTableIdAndStatusOrderByOpenedAtDesc(tableId, SessionStatus.OPEN)
                .map(this::applyCurrentAmounts);
    }

    /**
     * 伝票を開く（ご案内）。すでに開いていればそれを返す。
     *
     * <p>ほぼ同時に 2 人が QR を読むと、理論上は伝票が 2 つできる可能性があります。
     * DB の制約で完全に防ぐには「status が OPEN の行だけに効く一意制約」が必要ですが、
     * これは DB 製品ごとに書き方が違い移植性が下がるため採用していません。
     * 実店舗の規模ではまず起きず、起きてもホール画面から統合できるため、
     * ここでは「見つけたら使う、無ければ作る」で十分と判断しています。
     */
    @Transactional
    public TableSession openSession(Long tableId, int guestCount) {
        Optional<TableSession> existing = sessionRepository
                .findFirstByDiningTableIdAndStatusOrderByOpenedAtDesc(tableId, SessionStatus.OPEN);
        if (existing.isPresent()) {
            TableSession session = existing.get();
            if (guestCount > 0 && guestCount != session.getGuestCount()) {
                session.setGuestCount(guestCount);
            }
            return refresh(session);
        }

        DiningTable table = getById(tableId);
        ShopSetting setting = shopSettingService.current();
        LocalDateTime now = LocalDateTime.now();

        TableSession session = new TableSession(
                table, setting.businessDateOf(now),
                guestCount > 0 ? guestCount : 1,
                setting);
        session.recalculate(now, setting.isLateNight(now));

        TableSession saved = sessionRepository.save(session);
        log.info("伝票を開きました: 卓={} 人数={}", table.getName(), saved.getGuestCount());
        return saved;
    }

    /** 注文を受けるために、開いている伝票を必ず 1 つ返す。 */
    @Transactional
    public TableSession requireOpenSession(Long tableId) {
        return currentSession(tableId)
                .orElseThrow(() -> new OrderRejectedException(
                        "お席の伝票が見つかりませんでした。お手数ですがもう一度 QR コードを読み取ってください"));
    }

    @Transactional(readOnly = true)
    public TableSession getSession(Long sessionId) {
        return sessionRepository.findWithOrdersById(sessionId)
                .map(this::applyCurrentAmounts)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * ホール画面用：いま開いている伝票の一覧。
     *
     * <p>一覧でも詳細と同じ計算をしておきます。
     * ここを保存値のまま出すと、23 時をまたいだ直後に
     * 「一覧の金額」と「伝票を開いたときの金額」が食い違います。
     * 金額の表示が画面によって違うのは、現場でいちばん信用をなくすパターンです。
     */
    @Transactional(readOnly = true)
    public List<TableSession> openSessions() {
        List<TableSession> sessions = sessionRepository.findByStatusOrderByOpenedAtAsc(SessionStatus.OPEN);
        sessions.forEach(this::applyCurrentAmounts);
        return sessions;
    }

    /** 管理画面用：その営業日の伝票（新しい順）。 */
    @Transactional(readOnly = true)
    public List<TableSession> sessionsOf(LocalDate businessDate) {
        return sessionRepository.findByBusinessDateOrderByOpenedAtDesc(businessDate);
    }

    /** 人数を変更する（テーブルチャージが変わるので伝票を再計算する）。 */
    @Transactional
    public TableSession changeGuestCount(Long sessionId, int guestCount) {
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.isOpen()) {
            throw new IllegalStateException("会計済みの伝票は変更できません");
        }
        session.setGuestCount(guestCount);
        return refresh(session);
    }

    /**
     * お会計（伝票を締める）。
     *
     * @param applyLateNight 深夜料金を適用するか。既定は時刻から自動判定するが、
     *                       スタッフの判断で外せるようにしている
     */
    @Transactional
    public TableSession closeSession(Long sessionId, boolean applyLateNight, String staffName, String note) {
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.isOpen()) {
            throw new IllegalStateException("この伝票はすでに会計済みです");
        }
        hydrate(session);
        session.close(LocalDateTime.now(), applyLateNight, staffName, note);
        log.info("会計しました: 卓={} 人数={} 合計={}円",
                session.getDiningTable().getName(), session.getGuestCount(), session.getTotalAmount());
        return session;
    }

    /** 誤って会計した伝票を開け直す。 */
    @Transactional
    public TableSession reopenSession(Long sessionId, String staffName) {
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (session.isOpen()) {
            return session;
        }
        session.reopen();
        refresh(session);
        log.warn("会計を取り消しました: 卓={} 操作者={}", session.getDiningTable().getName(), staffName);
        return session;
    }

    /**
     * いまの時刻で伝票の金額を計算し直す。
     * 深夜料金は時刻で自動判定するので、23 時をまたぐと表示金額が変わります。
     */
    @Transactional
    public TableSession refresh(TableSession session) {
        return applyCurrentAmounts(session);
    }

    /**
     * 関連を読み込み、開いている伝票なら「いまの時刻」で金額を計算し直す。
     *
     * <p><b>読み取り用と書き込み用で同じ処理を使っている理由</b><br>
     * 呼び出し元が {@code @Transactional(readOnly = true)} なら、
     * Hibernate は変更を DB に書き戻しません（フラッシュしない）。
     * つまり同じメソッドが
     * <ul>
     *   <li>参照系（一覧・詳細の表示）から呼ばれたときは <b>表示用の再計算</b></li>
     *   <li>更新系（注文追加・人数変更）から呼ばれたときは <b>保存を伴う再計算</b></li>
     * </ul>
     * として振る舞います。計算そのものは 1 箇所（{@code TableSession#recalculate}）なので、
     * 「一覧では深夜料金が乗っていないのに詳細では乗っている」といった食い違いが起きません。
     */
    private TableSession applyCurrentAmounts(TableSession session) {
        hydrate(session);
        if (session.isOpen()) {
            ShopSetting setting = shopSettingService.currentReadOnly();
            LocalDateTime now = LocalDateTime.now();
            session.recalculate(now, setting.isLateNight(now));
        }
        return session;
    }

    /**
     * 画面を描くのに必要な関連を読み終えておく。
     *
     * <p>{@code open-in-view: false} なので、テンプレートの中では
     * 遅延読み込みができません（詳しくは docs/Java学習ガイド.md の STEP 5）。
     */
    private TableSession hydrate(TableSession session) {
        session.getDiningTable().getName();
        for (Order order : session.getOrders()) {
            for (OrderLine line : order.getLines()) {
                line.getOptions().size();
            }
        }
        return session;
    }

    // ── 例外 ─────────────────────────────────────────────────────

    public static class TableNotFoundException extends RuntimeException {
        public TableNotFoundException(Object key) {
            super("お席が見つかりません（%s）".formatted(key));
        }
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(Object key) {
            super("伝票が見つかりません（%s）".formatted(key));
        }
    }
}
