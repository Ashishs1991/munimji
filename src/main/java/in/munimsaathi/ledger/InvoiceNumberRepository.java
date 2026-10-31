package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceNumberRepository extends JpaRepository<InvoiceNumber, UUID> {

    Optional<InvoiceNumber> findByDocumentId(UUID documentId);

    Optional<InvoiceNumber> findByBusinessIdAndNumber(UUID businessId, long number);

    long countByBusinessId(UUID businessId);
}
