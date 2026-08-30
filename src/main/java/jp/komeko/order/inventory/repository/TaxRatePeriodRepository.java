package jp.komeko.order.inventory.repository;

import jp.komeko.order.inventory.domain.TaxRatePeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 税率マスタの読み書き。 */
public interface TaxRatePeriodRepository extends JpaRepository<TaxRatePeriod, Long> {

    /**
     * 指定日に有効な行を、区分ごとに引く。
     *
     * <p>並び順を {@code valid_from desc} にしているのは、
     * 万一期間が重なる行を登録してしまったときに<b>新しいほうを採る</b>ためです。
     * 黙って古い率を使い続けるより、直近の意図が反映されるほうが事故が小さい。
     */
    @Query("""
            select t from TaxRatePeriod t
            where t.rateClass = :rateClass
              and t.validFrom <= :date
              and (t.validTo is null or t.validTo >= :date)
            order by t.validFrom desc
            """)
    List<TaxRatePeriod> findActive(@Param("rateClass") String rateClass, @Param("date") LocalDate date);

    /** 全件を施行日順に（管理画面での確認用）。 */
    List<TaxRatePeriod> findAllByOrderByRateClassAscValidFromAsc();

    /**
     * 「終わりが決まっている行」のうち、指定日以降に期限を迎えるもの。
     * マスタ終端警告（制度変更の見落とし防止）に使う。
     */
    @Query("""
            select t from TaxRatePeriod t
            where t.validTo is not null and t.validTo >= :from
            order by t.validTo asc
            """)
    List<TaxRatePeriod> findExpiringFrom(@Param("from") LocalDate from);
}
