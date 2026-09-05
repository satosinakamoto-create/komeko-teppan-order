package jp.komeko.order.service;

import jp.komeko.order.domain.*;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    /**
     * その卓で<b>まだ会計が済んでいない</b>伝票を返す（無ければ空）。
     *
     * <p>お会計待ちの伝票も返します。ここを OPEN だけにすると、
     * お会計待ちの卓の QR をお客さまが読み直したときに
     * 「伝票が無い」と判定され、<b>人数選択の画面から新しい伝票が作られます</b>
     * （テーブルチャージが二重になる）。
     */
    @Transactional(readOnly = true)
    public Optional<TableSession> currentSession(Long tableId) {
        List<Long> ids = sessionRepository.findOpenSessionIds(tableId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return sessionRepository.findWithOrdersById(ids.get(0)).map(this::applyCurrentAmounts);
    }

    /**
     * 人数の申告が<b>どこから来たか</b>。
     *
     * <p>同じ「人数を N 名にする」でも、スタッフの操作とお客さまの操作では
     * 信用してよい範囲が違います。区別しないと、あとから QR を読んだ 1 人の
     * 古い画面から送られた「1名」が、ホールが 6 名でご案内した伝票を
     * 黙って上書きしてしまいます（テーブルチャージ ¥2,700 → ¥450）。
     */
    public enum GuestCountSource {
        /** ホール画面から。人が卓を見て入力しているので、増減どちらも信用する。 */
        STAFF,
        /** お客さまの端末から（{@code /t/{token}/start}）。減らす方向は反映しない。 */
        CUSTOMER
    }

    /**
     * 伝票を開く（ご案内）。すでに開いていればそれを返す。
     *
     * <p>ほぼ同時に 2 人が QR を読むと、理論上は伝票が 2 つできる可能性があります。
     * DB の制約で完全に防ぐには「status が OPEN の行だけに効く一意制約」が必要ですが、
     * これは DB 製品ごとに書き方が違い移植性が下がるため採用していません。
     * 実店舗の規模ではまず起きず、起きてもホール画面から統合できるため、
     * ここでは「見つけたら使う、無ければ作る」で十分と判断しています。
     *
     * <p>引数 2 つのこの形は<b>スタッフ操作</b>として扱います
     * （ホール画面の「ご案内」・デモデータ投入）。お客さまの端末から呼ぶときは
     * {@link #openSession(Long, int, GuestCountSource)} に {@link GuestCountSource#CUSTOMER}
     * を渡してください。
     */
    @Transactional
    public TableSession openSession(Long tableId, int guestCount) {
        return openSession(tableId, guestCount, GuestCountSource.STAFF);
    }

    /**
     * 伝票を開く（ご案内）。人数の申告元を指定する版。
     *
     * <p><b>既存の伝票が見つかったときは、読む前に行ロックを取ります。</b>
     * ここは「開いているか確かめて、人数を書いて、金額を計算し直す」＝
     * 読む→確かめる→書く、の典型です。ロック無しだと、確かめたあとに
     * お会計が締まってもそのままコミットでき、全カラム UPDATE によって
     * 会計（{@code closedAt} / {@code closedBy} / 確定した合計）が巻き戻ります。
     */
    @Transactional
    public TableSession openSession(Long tableId, int guestCount, GuestCountSource source) {
        // 先に ID だけを引くのが要点。ここでエンティティを読むと、その古い写しが
        // 永続化コンテキストに残り、ロック後に読み直しても同じものが返ってくる
        // （＝ロックを取った意味が無くなる）。詳しくは Repository 側の説明を参照。
        List<Long> openIds = sessionRepository.findOpenSessionIds(tableId);
        if (!openIds.isEmpty()) {
            Long openId = openIds.get(0);
            // ここは closeSession と違って、見つからなくても例外にしない。
            // 「ご案内」は伝票が無ければ作るのが仕事なので、消えていたら作りに行けばよい。
            sessionRepository.findWithLockById(openId);
            Optional<TableSession> locked = sessionRepository.findWithOrdersById(openId);
            // ロックを待っている間にお会計が締まっていることがある。
            // その場合はこの伝票には触らず、新しい来店として開き直す
            // （締めた伝票を開け直すのはスタッフの判断＝reopenSession の仕事）。
            if (locked.isPresent() && locked.get().isActive()) {
                TableSession session = locked.get();
                applyRequestedGuestCount(session, guestCount, source);
                return refresh(session);
            }
        }

        DiningTable table = getById(tableId);
        ShopSetting setting = shopSettingService.current();
        LocalDateTime now = LocalDateTime.now();

        TableSession session = new TableSession(
                table, setting.businessDateOf(now),
                guestCount > 0 ? guestCount : 1,
                setting);
        session.recalculate(setting::isLateNight);

        TableSession saved = sessionRepository.save(session);
        log.info("伝票を開きました: 卓={} 人数={}", table.getName(), saved.getGuestCount());
        return saved;
    }

    /**
     * 「すでに開いている伝票」に、申告された人数を反映してよいかを決める。
     *
     * <p><b>お客さま側からの操作では、人数を減らす方向を反映しません。</b>
     *
     * <p>理由は実害の大きさが方向によって全く違うからです。
     * {@code /t/**} は認証なし（{@code SecurityConfig} で permitAll）で、
     * QR さえ見えれば誰でも POST できます。そこで人数を減らせると、
     * <ul>
     *   <li>6 名でご案内した卓（チャージ ¥450 × 6 ＝ ¥2,700）が、
     *       遅れて QR を読んだ 1 人の古い画面から届く「1名」で ¥450 になる</li>
     *   <li>会計を締めるまで誰も気づけない（ログも通知も出ないため）</li>
     * </ul>
     * という取りっぱぐれが、悪意が無くても普通に起こります。
     *
     * <p><b>正当なケース（本当に人数が変わった）の扱い</b>
     * <ul>
     *   <li><b>増えた</b>…… お連れさまが後から合流した、というごく普通の流れです。
     *       そのまま反映します。チャージが増える方向なので取りっぱぐれず、
     *       もし間違っていてもお客さまが伝票を見て気づけますし、
     *       スタッフがホール画面から下げられます。</li>
     *   <li><b>減った</b>…… 人数の申告が多すぎた・先に帰った、という場合です。
     *       これは<b>スタッフが卓を見て判断すること</b>にします。
     *       お客さまの端末からは反映せず、代わりに
     *       「人数の変更はスタッフへ」とご案内を出します（呼び出し側の責務）。
     *       ホール画面からの変更は今までどおり増減とも効きます。</li>
     * </ul>
     */
    private void applyRequestedGuestCount(TableSession session, int guestCount, GuestCountSource source) {
        if (guestCount <= 0 || guestCount == session.getGuestCount()) {
            return;   // 申告なし、または変化なし
        }
        if (source == GuestCountSource.CUSTOMER && guestCount < session.getGuestCount()) {
            // 黙って捨てない。取りっぱぐれの兆候はログに残し、あとから追えるようにする
            log.warn("お客さま側から {} 名の申告がありましたが、すでに {} 名で開いている伝票のため据え置きました: 卓={}",
                    guestCount, session.getGuestCount(), session.getDiningTable().getName());
            return;
        }
        log.info("人数を {} 名 → {} 名 に変更しました（申告元={}）: 卓={}",
                session.getGuestCount(), guestCount, source, session.getDiningTable().getName());
        session.setGuestCount(guestCount);
    }

    /**
     * 伝票の行ロックを取る（SELECT … FOR UPDATE）。
     *
     * <p>「お会計で締める」と「追加注文の確定」を直列化するためのもの。
     * 両方がまずこのロックを取ることで、後から来たほうは先の操作の結果を
     * 見てから動く（closeSession と OrderService#placeOrder が使う）。
     *
     * <p>{@code MANDATORY} にしているのは、ロックは<b>呼び出し元の
     * トランザクションが終わるまで</b>握っていて初めて意味があるから。
     * トランザクションの外から呼ばれたら、ロックがすぐ手放されて
     * 直列化にならないので、設定ミスとして例外で落とす。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSession(Long sessionId) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
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
        // お会計待ちの卓も含める。含めないと、お会計待ちに入った瞬間に
        // その卓が空席として現れ、次のお客さまを案内できてしまう
        List<TableSession> sessions = sessionRepository.findActiveOrderByOpenedAtAsc();
        sessions.forEach(this::applyCurrentAmounts);
        return sessions;
    }

    /** 管理画面用：その営業日の伝票（新しい順）。 */
    @Transactional(readOnly = true)
    public List<TableSession> sessionsOf(LocalDate businessDate) {
        return sessionRepository.findByBusinessDateOrderByOpenedAtDesc(businessDate);
    }

    /**
     * 人数を変更する（テーブルチャージが変わるので伝票を再計算する）。
     *
     * <p>お会計との同時実行を直列化するため、<b>伝票を読む前に</b>行ロックを取ります。
     * ここを「読んでからチェック」で書くと、チェックを通ったあとにお会計が締まっても
     * 気づけず、全カラム UPDATE で {@code closedAt} / {@code closedBy} / 確定した合計まで
     * 巻き戻ります（＝お会計が例外も出さずに消える）。
     */
    @Transactional
    public TableSession changeGuestCount(Long sessionId, int guestCount) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.isActive()) {
            throw new IllegalStateException("会計済みの伝票は変更できません");
        }
        session.setGuestCount(guestCount);
        // 人数を減らしたとき、除外人数が来店人数を上回ったままにしない
        // （チャージが負になり、小計から他の品の代金が引かれる）
        session.setChargeExemptCount(session.getChargeExemptCount());
        return refresh(session);
    }

    /**
     * テーブルチャージを取らない人数を変える。
     *
     * <p>未就学児からお通し代を取らない、常連さんにサービスする、といった場面用です。
     *
     * <p><b>来店人数のほうを減らして辻褄を合わせないこと。</b>
     * 人数は売上の客数と客単価にも使われているので、
     * 6 名を 4 名に書き換えると、実際には 6 名ご来店しているのに
     * 客数が 4 名として集計されます。
     */
    @Transactional
    public TableSession changeChargeExemptCount(Long sessionId, int exemptCount) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.isActive()) {
            throw new IllegalStateException("会計済みの伝票は変更できません");
        }
        session.setChargeExemptCount(exemptCount);
        return refresh(session);
    }

    /**
     * お会計（伝票を締める）。
     *
     * <p>深夜料金は注文ごとに注文時刻で決まります（{@link TableSession#recalculate}）。
     * ここで渡す {@code applyLateNight} は、その計算をするかどうかの
     * <b>スタッフによる免除スイッチ</b>です。
     * false にすると、深夜帯の注文があっても深夜料金を一切かけずに締めます
     * （常連さんへのサービスなど、現場の判断のため）。
     *
     * @param applyLateNight false ならスタッフが深夜料金を免除したという意味
     */
    @Transactional
    public TableSession closeSession(Long sessionId, boolean applyLateNight, String staffName, String note,
                                     SettlementMethod paymentMethod) {
        if (paymentMethod == null) {
            // 既定を現金にすると、カードの選び忘れが黙って現金に化けて
            // レジ締めの数字が静かに狂う。選ばせるほうが安全
            throw new IllegalArgumentException("お支払い方法（現金／カード）を選んでください");
        }
        // 追加注文（placeOrder）との同時実行を直列化する。
        // 先にロックを取らないと、両方が自分のチェックを通過してから互いの結果を
        // 知らずにコミットし、「締めた伝票に注文がぶら下がる」（＝お客さまには
        // 承りましたと出たのに、誰にも請求されない）が起こり得る。
        // placeOrder 側も同じロックを最初に取る（OrderService 参照）。
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!session.isActive()) {
            throw new IllegalStateException("この伝票はすでに会計済みです");
        }
        hydrate(session);
        ShopSetting setting = shopSettingService.currentReadOnly();

        // ── 「免除」は、外すものが実際にあったときだけ記録する ──────────
        //
        // 以前は setLateNightWaived(!applyLateNight) を無条件にやっていた。
        // だがチェックボックスは深夜料金の対象が無い伝票では初期状態から
        // 外れている（誰も「免除の判断」をしていない）。それも免除として
        // 記録すると、昼帯に締めたほぼ全部の伝票に免除フラグが立ち、
        // 誤会計→取り消し→深夜帯の追加注文、という流れで
        // 本来かかるはずの割増が黙って消えていた（2026-08-22 のレビューで発覚）。
        //
        // そこで先に「ルールどおり計算したら割増が付くのか」を確かめ、
        // 付くはずのものをスタッフが外したときだけ waived を立てる。
        // （判定のあいだ waived を一旦 false にするのは、立ったままだと
        //   recalculate が NONE に強制されて「付くはずか」を計算できないため）
        session.setLateNightWaived(false);
        session.recalculate(setting::isLateNight);
        boolean wouldApply = session.isLateNightApplied();
        session.setLateNightWaived(wouldApply && !applyLateNight);

        session.close(LocalDateTime.now(), setting::isLateNight, staffName, note, paymentMethod);
        log.info("会計しました: 卓={} 人数={} 合計={}円 支払={}",
                session.getDiningTable().getName(), session.getGuestCount(),
                session.getTotalAmount(), paymentMethod.getLabel());
        return session;
    }

    /**
     * お会計待ちにする。<b>この間、その卓からは誰も注文できない。</b>
     *
     * <p>お客さまの「お会計おねがいします」でも、ホール画面の操作でも、ここへ来ます。
     *
     * <p>ロックを取るのは締めるときと同じ理由です。状態を変えるのも
     * 「読む → 確かめる → 書く」なので、注文の確定と交差します。
     * ここを直列化しておけば、「お会計待ちに入る直前に滑り込んだ注文」は
     * ちゃんと伝票に乗り、そのあとの注文は断られます。
     *
     * <p>すでにお会計待ちなら何もしません（連打・二重送信で壊れないように）。
     */
    @Transactional
    public TableSession startCheckout(Long sessionId) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (session.isClosed()) {
            throw new IllegalStateException("この伝票はすでに会計済みです");
        }
        session.startCheckout();
        log.info("お会計待ちにしました: 卓={}", session.getDiningTable().getName());
        return refresh(session);
    }

    /**
     * お会計待ちをやめて、また注文を受け付ける（「やっぱりもう一杯」）。
     *
     * <p><b>同じ伝票のまま戻します。</b>新しい伝票を開き直すと
     * テーブルチャージがもう一度かかってしまうためです。
     */
    @Transactional
    public TableSession resumeOrdering(Long sessionId) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        TableSession session = sessionRepository.findWithOrdersById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (session.isClosed()) {
            throw new IllegalStateException("会計済みの伝票です。開け直すには会計の取り消しを使ってください");
        }
        session.resumeOrdering();
        log.info("注文の受付を再開しました: 卓={}", session.getDiningTable().getName());
        return refresh(session);
    }

    /**
     * 誤って会計した伝票を開け直す。
     *
     * <p>ここも「読む → 開いているか確かめる → 書く」なので、読む前に行ロックを取ります。
     * 取らないと、二人が同時に取り消した／取り消しとお会計が交差した場合に、
     * 古い写しで上書きされて状態が行ったり来たりします。
     */
    @Transactional
    public TableSession reopenSession(Long sessionId, String staffName) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
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
     * 伝票の金額を、いまの店舗設定のルールで計算し直す。
     *
     * <p>深夜料金は<b>注文ごとに、その注文が出された時刻</b>で判定されるので、
     * ただ 23 時をまたいだだけでは金額は変わりません。
     * 変わるのは、深夜帯に入ってから注文が追加されたときと、
     * 店長が深夜料金の設定（時刻・割増率）を変えたときです。
     */
    @Transactional
    public TableSession refresh(TableSession session) {
        return applyCurrentAmounts(session);
    }

    /**
     * 関連を読み込み、開いている伝票なら金額を計算し直す。
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
        if (session.isActive()) {
            ShopSetting setting = shopSettingService.currentReadOnly();
            session.recalculate(setting::isLateNight);
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
