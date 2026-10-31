package in.munimsaathi.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BatchRepository extends JpaRepository<Batch, UUID> {

    long countByProductId(UUID productId);

    Optional<Batch> findByProductIdAndExpiryDate(UUID productId, LocalDate expiryDate);
}
