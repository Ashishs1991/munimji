package in.munimsaathi.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, UUID> {

    Optional<ProductAlias> findByBusinessIdAndNormalizedAliasAndSupplierPartyIdIsNull(
            UUID businessId,
            String normalizedAlias
    );
}
