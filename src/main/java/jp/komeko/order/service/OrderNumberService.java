package jp.komeko.order.service;

import jp.komeko.order.domain.DailyCounter;
import jp.komeko.order.repository.DailyCounterRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 注文番号（お客さんを呼び出す番号）の採番。
 *
 * <p><b>やりたいこと</b><br>
 * 「営業日ごとに 101, 102, 103 … と重複なく番号を振る」。
 * 単純に見えますが、2 人が同時に注文したときに同じ番号を渡してしまうと
 * 商品の渡し間違いという実害が出ます。ここは慎重に作ります。
 *
 * <p><b>やってはいけない実装</b>
 * <pre>
 *   int max = orderRepository.findMaxOrderNumber(today);   // ← 2人が同時に読むと
 *   int next = max + 1;                                     //    同じ値になる
 * </pre>
 * これは競合状態（レースコンディション）そのものです。
 *
 * <p><b>この実装のやり方</b>
 * <ol>
 *   <li>営業日ごとに 1 行だけのカウンタ表（{@link DailyCounter}）を用意する</li>
 *   <li>その行を <b>排他ロック</b>（{@code SELECT … FOR UPDATE}）してから +1 する</li>
 *   <li>ロックを握る時間を最短にするため、採番だけ独立したトランザクションで行う</li>
 * </ol>
 *
 * <p><b>番号は飛んでもよい</b><br>
 * 採番を別トランザクションにしているので、そのあと注文の保存が失敗すると
 * 番号がひとつ飛びます。実運用では問題ありません。
 * それよりも「番号を再利用して別のお客さんに同じ番号を渡す」ほうが事故になります。
 */
@Service
public class OrderNumberService {

    /** 初回作成が他のリクエストとぶつかったときのやり直し回数。 */
    private static final int MAX_ATTEMPTS = 3;

    private final DailyCounterStore store;
    private final DailyCounterRepository repository;

    public OrderNumberService(DailyCounterStore store, DailyCounterRepository repository) {
        this.store = store;
        this.repository = repository;
    }

    /**
     * 次の注文番号を払い出す。
     *
     * @param businessDate 営業日
     * @param startNumber  その日の 1 件目に振る番号（例: 101）
     */
    public int next(LocalDate businessDate, int startNumber) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {

            // ① すでにカウンタ行があれば、ロックして +1 して終わり（通常はここで返る）
            Optional<Integer> number = store.tryIncrement(businessDate);
            if (number.isPresent()) {
                return number.get();
            }

            // ② その日の最初の注文。カウンタ行を作る。
            //    ほぼ同時に 2 人が最初の注文をすると片方は主キー重複で失敗するが、
            //    それは「相手が先に作ってくれた」ということなので、①へ戻ればよい。
            try {
                store.create(businessDate, startNumber - 1);
            } catch (DataIntegrityViolationException e) {
                // 想定内。次のループで読み直す。
            }
        }
        throw new IllegalStateException(
                "注文番号を採番できませんでした（営業日=%s）".formatted(businessDate));
    }

    /** その日にすでに何番まで払い出したか（管理画面の表示用）。 */
    @Transactional(readOnly = true)
    public int lastIssued(LocalDate businessDate) {
        return repository.findById(businessDate)
                .map(DailyCounter::getLastNumber)
                .orElse(0);
    }
}
