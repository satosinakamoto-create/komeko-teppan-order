package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.DeductionRatePeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 経過措置の控除率マスタの読み書き。 */
public interface DeductionRatePeriodRepository extends JpaRepository<DeductionRatePeriod, Long> {

    @Query("""
            select d from DeductionRatePeriod d
            where d.validFrom <= :date
              and (d.validTo is null or d.validTo >= :date)
            order by d.validFrom desc
            """)
    List<DeductionRatePeriod> findActive(@Param("date") LocalDate date);

    List<DeductionRatePeriod> findAllByOrderByValidFromAsc();

    @Query("""
            select d from DeductionRatePeriod d
            where d.validTo is not null and d.validTo >= :from
            order by d.validTo asc
            """)
    List<DeductionRatePeriod> findExpiringFrom(@Param("from") LocalDate from);
}
