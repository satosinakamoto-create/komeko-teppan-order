package jp.komeko.order.repository;

import jp.komeko.order.domain.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<StaffUser> findAllByOrderByIdAsc();
}
