package jp.komeko.order.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;

/**
 * 営業日ごとの注文番号カウンタ。
 *
 * <p><b>なぜ専用テーブルが要るのか</b><br>
 * 「今日の注文の最大番号 + 1」を毎回 SELECT して採番すると、
 * 2 人が同時に注文したときに同じ番号が振られてしまう可能性があります（競合状態）。
 * 行ロックできる専用テーブルを 1 行だけ用意し、
 * 「ロック → +1 → 保存」を 1 トランザクションで行うことで重複を防ぎます。
 * 実際のロックは {@code DailyCounterRepository#lockByBusinessDate} で行っています。
 */
@Entity
@Table(name = "daily_counter")
public class DailyCounter implements Persistable<LocalDate> {

    /** 営業日そのものを主キーにする（1 日 1 行）。 */
    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** その日に最後に払い出した番号。 */
    @Column(nullable = false)
    private int lastNumber;

    /**
     * まだ DB に無い（新規）かどうか。
     *
     * <p>{@code @Transient} は「この項目は DB に保存しない」という指定です。
     *
     * <p><b>なぜこのフラグが必要か</b><br>
     * Spring Data の {@code save()} は「主キーが null なら INSERT、そうでなければ UPDATE」
     * と判断します。このクラスは主キーを自分で決める（営業日）ので、
     * 新規でも主キーが埋まっており、放っておくと <b>UPDATE 扱い</b>になります。
     * すると Hibernate は「まず SELECT して、無ければ INSERT」という動きをするため、
     * 2 人が同時に初回注文をしたときに、後から来た側がすでにある行を
     * 上書きしてカウンタを巻き戻してしまう恐れがあります。
     *
     * <p>{@link Persistable} を実装して「新規なら必ず INSERT」と伝えることで、
     * 衝突時はきちんと主キー重複エラーになり、呼び出し側が読み直せるようになります。
     */
    @Transient
    private boolean isNew = true;

    protected DailyCounter() {
    }

    public DailyCounter(LocalDate businessDate, int lastNumber) {
        this.businessDate = businessDate;
        this.lastNumber = lastNumber;
    }

    @Override
    public LocalDate getId() {
        return businessDate;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** 保存・読み込みが済んだら「新規ではない」に切り替える。 */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /** 次の番号を払い出す。 */
    public int next() {
        this.lastNumber += 1;
        return this.lastNumber;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getLastNumber() {
        return lastNumber;
    }
}
