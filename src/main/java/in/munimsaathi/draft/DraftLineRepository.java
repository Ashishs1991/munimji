package in.munimsaathi.draft;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DraftLineRepository extends JpaRepository<DraftLine, UUID> {

    List<DraftLine> findByDraftIdOrderByLineNumberAsc(UUID draftId);
}
