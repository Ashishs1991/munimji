package in.munimsaathi.draft;

import java.util.List;
import java.util.UUID;

public record DraftProposal(
        UUID id,
        long version,
        String contentHash,
        DraftStatus status,
        List<String> missingFields,
        Long amountPaise
) {
}
