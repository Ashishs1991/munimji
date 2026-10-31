package in.munimsaathi.eway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EwayDraftRepository extends JpaRepository<EwayDraft, UUID> {

    Optional<EwayDraft> findByBusinessIdAndDocumentId(UUID businessId, UUID documentId);
}
