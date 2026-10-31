package in.munimsaathi.forecast;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

public interface SalesCoverageRepository extends JpaRepository<SalesCoverage, UUID> {

    boolean existsByBusinessIdAndCoveredOn(UUID businessId, LocalDate coveredOn);
}
