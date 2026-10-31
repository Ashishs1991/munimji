package in.munimsaathi.ledger;

import java.util.UUID;

public record PurchaseBillOutcome(UUID documentId, UUID reviewId) {

    public boolean needsReview() {
        return reviewId != null;
    }
}
