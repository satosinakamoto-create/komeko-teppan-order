package jp.komeko.order.repository;

import jp.komeko.order.domain.OptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OptionGroupRepository extends JpaRepository<OptionGroup, Long> {

    @Query("""
            select g from OptionGroup g
            left join fetch g.choices
            join fetch g.menuItem
            where g.id = :id
            """)
    Optional<OptionGroup> findByIdWithChoices(@Param("id") Long id);
}
