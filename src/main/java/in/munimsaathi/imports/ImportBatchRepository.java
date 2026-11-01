package in.munimsaathi.imports;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    Optional<ImportBatch> findByBusinessIdAndFileHash(UUID businessId, String fileHash);

    List<ImportBatch> findByBusinessId(UUID businessId);

    long countByBusinessId(UUID businessId);
}
