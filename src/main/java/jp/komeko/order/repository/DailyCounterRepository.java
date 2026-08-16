package jp.komeko.order.repository;

import jakarta.persistence.LockModeType;
import jp.komeko.order.domain.DailyCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 注文番号カウンタの DB アクセス。
 */
public interface DailyCounterRepository extends JpaRepository<DailyCounter, LocalDate> {

    /**
     * 営業日の行を <b>排他ロック付き</b> で取得する。
     *
     * <p>{@code PESSIMISTIC_WRITE} は SQL の {@code SELECT ... FOR UPDATE} に相当します。
     * 誰かがこの行をロックしている間、他のトランザクションは待たされます。
     * これにより「2 人が同時に注文して同じ番号が振られる」事故を防ぎます。
     *
     * <p>ロックはトランザクションが終わると自動的に解放されます。
     * 呼び出し側は必ず {@code @Transactional} の中で使ってください。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DailyCounter c where c.businessDate = :businessDate")
    Optional<DailyCounter> lockByBusinessDate(@Param("businessDate") LocalDate businessDate);
}
