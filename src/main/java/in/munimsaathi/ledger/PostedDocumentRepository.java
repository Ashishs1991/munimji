package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PostedDocumentRepository extends JpaRepository<PostedDocument, UUID> {

    Optional<PostedDocument> findByOperationId(UUID operationId);
}
