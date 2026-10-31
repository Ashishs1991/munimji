package in.munimsaathi.eway;

import java.util.UUID;

public record EwayDraftView(
        UUID id,
        UUID documentId,
        String status,
        String environment,
        int version,
        String ewayBillNo,
        String ewayBillDate,
        String validUpto,
        String error,
        String label
) {
}
