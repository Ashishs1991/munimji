package in.munimsaathi.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockThresholdRepository extends JpaRepository<StockThreshold, UUID> {

    Optional<StockThreshold> findByBusinessIdAndProductId(UUID businessId, UUID productId);

    List<StockThreshold> findByBusinessId(UUID businessId);
}
