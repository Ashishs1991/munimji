package in.munimsaathi.draft;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DraftRepository extends JpaRepository<Draft, UUID> {

    List<Draft> findByBusinessIdAndStatus(UUID businessId, DraftStatus status);

    List<Draft> findByBusinessIdAndStatusIn(UUID businessId, Collection<DraftStatus> statuses);
}
