package in.munimsaathi.forecast;

import java.math.BigDecimal;

public record ReorderSuggestion(BigDecimal quantity, String missing) {
}
