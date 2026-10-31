package in.munimsaathi.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CountLineRepository extends JpaRepository<CountLine, UUID> {

    List<CountLine> findBySessionIdOrderByProductIdAsc(UUID sessionId);

    boolean existsBySessionIdAndProductId(UUID sessionId, UUID productId);
}
