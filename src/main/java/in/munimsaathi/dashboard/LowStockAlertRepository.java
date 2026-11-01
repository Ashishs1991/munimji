package in.munimsaathi.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LowStockAlertRepository extends JpaRepository<LowStockAlert, UUID> {

    Optional<LowStockAlert> findByBusinessIdAndProductIdAndResolvedAtIsNull(UUID businessId, UUID productId);

    List<LowStockAlert> findByBusinessIdAndResolvedAtIsNull(UUID businessId);

    long countByBusinessIdAndProductId(UUID businessId, UUID productId);
}
