package in.munimsaathi.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceCounterRepository extends JpaRepository<InvoiceCounter, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select counter from InvoiceCounter counter where counter.businessId = :businessId")
    Optional<InvoiceCounter> lock(@Param("businessId") UUID businessId);
}
