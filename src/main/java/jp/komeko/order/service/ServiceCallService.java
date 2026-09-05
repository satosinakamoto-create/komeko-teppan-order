package jp.komeko.order.service;

import jp.komeko.order.domain.ServiceCall;
import jp.komeko.order.domain.ServiceCallType;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.ServiceCallRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * お客さまからの呼び出し（スタッフを呼ぶ／お会計をお願いする）。
 *
 * <p>持っていく物（お水・おしぼり・取り皿…）はここを通りません。
 * あちらは ¥0 の商品として {@code OrderService} を通り、厨房ボードに出ます。
 * 分けている理由は {@link ServiceCallType} に書いてあります。
 */
@Service
public class ServiceCallService {

    private static final Logger log = LoggerFactory.getLogger(ServiceCallService.class);

    /**
     * 何時間前までの呼び出しをホール画面に出すか。
     *
     * <p>営業日で絞ると、5:00 をまたいだ卓の呼び出しが画面から消えます
     * （{@code ServiceCallRepository#findPending} 参照）。
     * 代わりに時刻で線を引きます。6 時間は
     * {@code OrderService.CARRY_OVER_WINDOW} と同じ長さで、
     * 「まだ席にいる可能性がある」の上限としてそろえてあります。
     */
    private static final Duration WINDOW = Duration.ofHours(6);

    /**
     * 同じ卓から続けて呼ばれたときに、新しい行を作らない間隔。
     *
     * <p>反応が無いと人は何度も押します。押すたびに行が増えると、
     * ホール画面が同じ卓で埋まって<b>他の卓の呼び出しが見えなくなります</b>。
     * 押した本人には「承りました」と出るので、握りつぶしにはなりません。
     */
    private static final Duration DEDUPE = Duration.ofMinutes(3);

    private final ServiceCallRepository calls;
    private final TableSessionRepository sessions;

    public ServiceCallService(ServiceCallRepository calls, TableSessionRepository sessions) {
        this.calls = calls;
        this.sessions = sessions;
    }

    /**
     * 呼び出しを 1 件受け付ける。
     *
     * <p>直前に同じ卓・同じ種類の未対応があれば、それを返して新しくは作りません。
     */
    @Transactional
    public ServiceCall call(Long sessionId, ServiceCallType type) {
        TableSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("伝票が見つかりません: " + sessionId));

        LocalDateTime since = LocalDateTime.now().minus(DEDUPE);
        for (ServiceCall existing : calls.findPending(LocalDateTime.now().minus(WINDOW))) {
            if (existing.getType() == type
                    && existing.getSession().getId().equals(sessionId)
                    && existing.getCreatedAt().isAfter(since)) {
                log.info("呼び出しは受付済みです（重ねて作りません）: 卓={} 種類={}",
                        session.getDiningTable().getName(), type);
                return existing;
            }
        }

        ServiceCall saved = calls.save(new ServiceCall(session, type));
        log.info("呼び出しを受け付けました: 卓={} 種類={}",
                session.getDiningTable().getName(), type);
        return saved;
    }

    /** ホール画面用：まだ対応していない呼び出し（古い順）。 */
    @Transactional(readOnly = true)
    public List<ServiceCall> pending() {
        return calls.findPending(LocalDateTime.now().minus(WINDOW));
    }

    /** ヘッダー用：まだ対応していない件数。 */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return calls.countPending(LocalDateTime.now().minus(WINDOW));
    }

    /** 対応済みにする。すでに済んでいれば何もしない（同時押し対策は entity 側）。 */
    @Transactional
    public void handle(Long id, String staffName) {
        calls.findById(id).ifPresent(c -> {
            c.handle(staffName);
            log.info("呼び出しに対応しました: id={} 担当={}", id, staffName);
        });
    }
}
