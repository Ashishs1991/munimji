package in.munimsaathi.imports;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImportRowRepository extends JpaRepository<ImportRow, UUID> {

    List<ImportRow> findByImportIdOrderByRowNumberAsc(UUID importId);
}
