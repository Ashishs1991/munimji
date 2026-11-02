package in.munimsaathi.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {

    List<Party> findByBusinessIdAndNormalizedNameOrderByDisplayNameAsc(UUID businessId, String normalizedName);

    List<Party> findByBusinessIdOrderByDisplayNameAsc(UUID businessId);
}
