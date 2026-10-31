package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PostedDocumentRepository extends JpaRepository<PostedDocument, UUID> {

    Optional<PostedDocument> findByOperationId(UUID operationId);

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
