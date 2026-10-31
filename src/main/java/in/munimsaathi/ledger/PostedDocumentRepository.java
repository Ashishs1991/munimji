package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
