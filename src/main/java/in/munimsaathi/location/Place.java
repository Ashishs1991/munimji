package in.munimsaathi.location;

import java.math.BigDecimal;
import java.util.UUID;

public record Place(UUID locationId, BigDecimal available, boolean currentlyStocked) {
}
