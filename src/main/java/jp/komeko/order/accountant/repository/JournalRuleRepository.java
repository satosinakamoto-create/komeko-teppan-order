package jp.komeko.order.accountant.repository;

import jp.komeko.order.accountant.domain.JournalRule;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 仕訳の対応表の出し入れ。 */
public interface JournalRuleRepository extends JpaRepository<JournalRule, Long> {

    /**
     * 「費目 × 税率 × 控除率」で 1 行引く。
     *
     * <p>3 つそろって初めて税区分が決まります。同じ食材の 8% でも、
     * 全額控除と経過措置 80% では会計ソフト上まったく別の区分になるためです。
     */
    @Query("""
            select r from JournalRule r
            where r.category = :category
              and r.taxRatePercent = :taxRate
              and r.deductionRatePercent = :deductionRate
            """)
    Optional<JournalRule> find(@Param("category") PurchaseCategory category,
                               @Param("taxRate") int taxRatePercent,
                               @Param("deductionRate") int deductionRatePercent);

    List<JournalRule> findAllByOrderByCategoryAscTaxRatePercentDescDeductionRatePercentDesc();
}
