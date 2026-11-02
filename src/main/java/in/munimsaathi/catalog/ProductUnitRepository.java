package in.munimsaathi.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductUnitRepository extends JpaRepository<ProductUnit, UUID> {

    java.util.List<ProductUnit> findByProductId(UUID productId);

    Optional<ProductUnit> findByProductIdAndUnitLabel(UUID productId, String unitLabel);
}
