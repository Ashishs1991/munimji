package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BillReviewRepository extends JpaRepository<BillReview, UUID> {
}
