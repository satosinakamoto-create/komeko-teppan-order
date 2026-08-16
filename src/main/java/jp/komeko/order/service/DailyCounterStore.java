package jp.komeko.order.service;

import jp.komeko.order.domain.DailyCounter;
import jp.komeko.order.repository.DailyCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 注文番号カウンタへの、ごく短いトランザクションだけを担当するクラス。
 *
 * <p><b>なぜ {@link OrderNumberService} と 2 つに分かれているのか</b><br>
 * Spring の {@code @Transactional} は<b>プロキシ（身代わりオブジェクト）</b>で実現されています。
 * そのため <b>同じクラスの中のメソッドを自分で呼んでもトランザクションは始まりません</b>
 * （プロキシを経由しないため）。これは Spring で最もよくある落とし穴のひとつです。
 *
 * <pre>
 *   ✗ 効かない            ○ 効く
 *   class A {             class A {            class B {
 *     void x() {            B b;                 @Transactional
 *       this.y();           void x() {             void y() { … }
 *     }                       b.y();  ←プロキシ経由  }
 *     @Transactional        }
 *     void y() {…}        }
 *   }
 * </pre>
 *
 * <p>「行を作る」「行をロックして +1 する」をそれぞれ独立したトランザクションで
 * 実行したいので、呼び出し元（{@link OrderNumberService}）とは別のクラスに置いています。
 *
 * <p>もう 1 つの理由は<b>失敗の閉じ込め</b>です。
 * 主キー重複でトランザクションが失敗すると、そのトランザクションはもう使えません
 * （たとえ例外を握りつぶしてもコミット時にエラーになります）。
 * 別トランザクションに切り離しておけば、失敗しても呼び出し元は無傷で、
 * 落ち着いて読み直すことができます。
 */
@Service
public class DailyCounterStore {

    private final DailyCounterRepository repository;

    public DailyCounterStore(DailyCounterRepository repository) {
        this.repository = repository;
    }

    /**
     * カウンタ行をロックして 1 つ進め、払い出した番号を返す。
     * 行がまだ無ければ空を返す（作るのは {@link #create} の役目）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> tryIncrement(LocalDate businessDate) {
        return repository.lockByBusinessDate(businessDate).map(counter -> {
            int number = counter.next();
            repository.saveAndFlush(counter);
            return number;
        });
    }

    /**
     * カウンタ行を新規作成する。
     *
     * <p>同じ営業日の行がすでにあると主キー重複で
     * {@code DataIntegrityViolationException} が投げられます。
     * <b>ここでは握りつぶしません。</b> 呼び出し元（別トランザクション）で受け止めて、
     * 「他の誰かが先に作った」と判断して読み直してもらいます。
     *
     * @param lastNumber その日にまだ 1 件も出していない状態を表す値（開始番号 − 1）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(LocalDate businessDate, int lastNumber) {
        repository.saveAndFlush(new DailyCounter(businessDate, lastNumber));
    }
}
