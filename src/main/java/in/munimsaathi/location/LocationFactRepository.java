package in.munimsaathi.location;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LocationFactRepository extends JpaRepository<LocationFact, UUID> {

    java.util.List<LocationFact> findTop100ByBusinessIdOrderByNotedAtDesc(UUID businessId);

    Optional<LocationFact> findFirstByBusinessIdAndLabelOrderByNotedAtDesc(UUID businessId, String label);

    Optional<LocationFact> findByOperationId(UUID operationId);

    Optional<LocationFact> findFirstByBusinessIdAndProductIdOrderByNotedAtDesc(UUID businessId, UUID productId);
}
