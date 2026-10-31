package in.munimsaathi.forecast;

import java.math.BigDecimal;

public record Forecast(
        String eligibility,
        int horizonDays,
        BigDecimal forecastQuantity,
        BigDecimal grossQuantity,
        BigDecimal returnQuantity,
        int coveredDays,
        int positiveDays,
        String methodVersion,
        String limitation
) {
}
