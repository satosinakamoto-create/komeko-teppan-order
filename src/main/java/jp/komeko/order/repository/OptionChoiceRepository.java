package jp.komeko.order.repository;

import jp.komeko.order.domain.OptionChoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionChoiceRepository extends JpaRepository<OptionChoice, Long> {
}
