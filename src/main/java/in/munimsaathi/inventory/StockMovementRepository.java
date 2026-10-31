package in.munimsaathi.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByOperationId(UUID operationId);

    Optional<StockMovement> findFirstByBusinessIdAndProductIdAndOwnershipOrderByOccurredAtDesc(
            UUID businessId,
            UUID productId,
            Ownership ownership
    );
}
