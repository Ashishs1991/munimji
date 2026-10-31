package in.munimsaathi.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByOperationId(UUID operationId);

    @Query("""
            select movement.locationId as locationId,
                   movement.productId as productId,
                   sum(movement.signedBaseQuantity) as quantity
            from StockMovement movement
            where movement.businessId = :businessId
              and movement.ownership = :ownership
              and movement.occurredAt < :until
            group by movement.locationId, movement.productId
            """)
    List<StockSlice> summedUntil(
            @Param("businessId") UUID businessId,
            @Param("ownership") Ownership ownership,
            @Param("until") Instant until
    );

    @Query("""
            select movement.locationId as locationId,
                   movement.productId as productId,
                   sum(movement.signedBaseQuantity) as quantity
            from StockMovement movement
            where movement.businessId = :businessId
              and movement.ownership = :ownership
              and movement.occurredAt < :until
              and movement.batchId is null
            group by movement.locationId, movement.productId
            """)
    List<StockSlice> summedUnknownExpiryUntil(
            @Param("businessId") UUID businessId,
            @Param("ownership") Ownership ownership,
            @Param("until") Instant until
    );

    Optional<StockMovement> findFirstByBusinessIdAndProductIdAndOwnershipOrderByOccurredAtDesc(
            UUID businessId,
            UUID productId,
            Ownership ownership
    );
}
