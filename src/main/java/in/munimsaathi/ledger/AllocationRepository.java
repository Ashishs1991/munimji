package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AllocationRepository extends JpaRepository<Allocation, UUID> {

    @Query("""
            select coalesce(sum(allocation.amountPaise), 0)
            from Allocation allocation
            where allocation.targetDocumentId = :documentId
            """)
    long allocatedTo(@Param("documentId") UUID documentId);

    Optional<Allocation> findBySourceEntryId(UUID sourceEntryId);
}
