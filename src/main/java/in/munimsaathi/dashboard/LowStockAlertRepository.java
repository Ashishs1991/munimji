package in.munimsaathi.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LowStockAlertRepository extends JpaRepository<LowStockAlert, UUID> {

    Optional<LowStockAlert> findByBusinessIdAndProductIdAndResolvedAtIsNull(UUID businessId, UUID productId);

    long countByBusinessIdAndProductId(UUID businessId, UUID productId);
}
