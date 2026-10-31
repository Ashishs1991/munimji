package in.munimsaathi.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

public final class BillFingerprint {

    private BillFingerprint() {
    }

    public static String of(
            UUID supplierId,
            LocalDate periodStart,
            LocalDate periodEnd,
            UUID productId,
            BigDecimal baseQuantity,
            long amountPaise
    ) {
        String canonical = supplierId
                + "|"
                + periodStart
                + "|"
                + periodEnd
                + "|"
                + productId
                + "|"
                + baseQuantity.stripTrailingZeros().toPlainString()
                + "|"
                + amountPaise;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
