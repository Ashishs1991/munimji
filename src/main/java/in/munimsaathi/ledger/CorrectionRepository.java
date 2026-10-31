package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CorrectionRepository extends JpaRepository<Correction, UUID> {

    Optional<Correction> findByOperationId(UUID operationId);
}
