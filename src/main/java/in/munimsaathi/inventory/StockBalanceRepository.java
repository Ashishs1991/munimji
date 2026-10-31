package in.munimsaathi.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.businessId = :businessId
              and b.productId = :productId
              and b.locationId = :locationId
              and b.ownership = :ownership
              and b.batchId is null
            """)
    Optional<StockBalance> lockWithoutBatch(
            @Param("businessId") UUID businessId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("ownership") Ownership ownership
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from StockBalance b
            where b.businessId = :businessId
              and b.productId = :productId
              and b.locationId = :locationId
              and b.ownership = :ownership
              and b.batchId = :batchId
            """)
    Optional<StockBalance> lockBatch(
            @Param("businessId") UUID businessId,
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("ownership") Ownership ownership,
            @Param("batchId") UUID batchId
    );

    Optional<StockBalance> findByBusinessIdAndProductIdAndLocationIdAndOwnershipAndBatchIdIsNull(
            UUID businessId,
            UUID productId,
            UUID locationId,
            Ownership ownership
    );

    Optional<StockBalance> findByBusinessIdAndProductIdAndLocationIdAndOwnershipAndBatchId(
            UUID businessId,
            UUID productId,
            UUID locationId,
            Ownership ownership,
            UUID batchId
    );
}
