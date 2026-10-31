package in.munimsaathi.imports;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    Optional<ImportBatch> findByBusinessIdAndFileHash(UUID businessId, String fileHash);

    long countByBusinessId(UUID businessId);
}
