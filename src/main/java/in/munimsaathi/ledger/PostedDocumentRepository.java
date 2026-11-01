package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostedDocumentRepository extends JpaRepository<PostedDocument, UUID> {

    Optional<PostedDocument> findByOperationId(UUID operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from PostedDocument document where document.id = :id")
    Optional<PostedDocument> lockById(@Param("id") UUID id);

    List<PostedDocument> findByBusinessIdAndOccurredOn(UUID businessId, LocalDate occurredOn);

    long countByBusinessIdAndFinanceStatusAndOccurredOnLessThanEqual(
            UUID businessId,
            FinanceStatus financeStatus,
            LocalDate occurredOn
    );

    boolean existsByBusinessIdAndFingerprint(UUID businessId, String fingerprint);

    boolean existsByBusinessIdAndPartyIdAndBillNumberAndPeriodStartAndPeriodEnd(
            UUID businessId,
            UUID partyId,
            String billNumber,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    long countByPartyIdAndKind(UUID partyId, DocumentKind kind);

    @Query("""
            select coalesce(sum(document.totalPaise), 0)
            from PostedDocument document
            where document.sourceDocumentId = :sourceId and document.kind = :kind
            """)
    long linkedPaise(@Param("sourceId") UUID sourceId, @Param("kind") DocumentKind kind);
}
