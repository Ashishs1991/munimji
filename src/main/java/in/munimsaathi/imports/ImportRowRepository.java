package in.munimsaathi.imports;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ImportRowRepository extends JpaRepository<ImportRow, UUID> {

    List<ImportRow> findByImportIdOrderByRowNumberAsc(UUID importId);

    @Query(value = """
            select exists (
                select 1 from import_rows r
                join imports b on b.id = r.import_id
                where b.business_id = :businessId
                  and b.id <> :importId
                  and b.status = 'ACTIVE'
                  and r.source_id = :sourceId
                  and r.status = 'MAPPED'
            )
            """, nativeQuery = true)
    boolean alreadyActive(@Param("businessId") UUID businessId, @Param("importId") UUID importId, @Param("sourceId") String sourceId);
}
