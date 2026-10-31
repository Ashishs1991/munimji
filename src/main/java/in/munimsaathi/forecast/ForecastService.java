package in.munimsaathi.forecast;

import in.munimsaathi.inventory.Ownership;
import in.munimsaathi.inventory.StockBalance;
import in.munimsaathi.inventory.StockBalanceRepository;
import in.munimsaathi.inventory.StockMovement;
import in.munimsaathi.inventory.StockMovementRepository;
import in.munimsaathi.inventory.StockReason;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ForecastService {

    static final int WINDOW_DAYS = 28;
    static final int HORIZON_DAYS = 7;
    static final int MINIMUM_POSITIVE_DAYS = 8;
    static final String METHOD = "28-day-mean-v1";
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private final TenantContext tenants;
    private final SalesCoverageRepository coverage;
    private final StockMovementRepository movements;
    private final StockBalanceRepository balances;

    public ForecastService(
            TenantContext tenants,
            SalesCoverageRepository coverage,
            StockMovementRepository movements,
            StockBalanceRepository balances
    ) {
        this.tenants = tenants;
        this.coverage = coverage;
        this.movements = movements;
        this.balances = balances;
    }

    @Transactional
    public void cover(UUID userId, LocalDate from, LocalDate through) {
        UUID businessId = tenants.currentBusinessId(userId);
        if (from == null || through == null || through.isBefore(from)) {
            throw new IllegalArgumentException("Name the covered dates.");
        }
        for (LocalDate day = from; !day.isAfter(through); day = day.plusDays(1)) {
            if (!coverage.existsByBusinessIdAndCoveredOn(businessId, day)) {
                coverage.save(new SalesCoverage(UUID.randomUUID(), businessId, day));
            }
        }
    }

    @Transactional(readOnly = true)
    public Forecast forecast(UUID userId, UUID productId, LocalDate asOf) {
        UUID businessId = tenants.currentBusinessId(userId);
        Window window = window(businessId, productId, asOf);
        if (!window.eligible()) {
            return new Forecast(
                    "INSUFFICIENT_HISTORY",
                    HORIZON_DAYS,
                    null,
                    window.gross(),
                    window.returns(),
                    window.coveredDays(),
                    window.positiveDays(),
                    METHOD,
                    "insufficient reliable history"
            );
        }
        return new Forecast(
                "PROVISIONAL",
                HORIZON_DAYS,
                scale(window.gross(), HORIZON_DAYS),
                window.gross(),
                window.returns(),
                window.coveredDays(),
                window.positiveDays(),
                METHOD,
                "provisional baseline, not a validated accuracy claim"
        );
    }

    @Transactional(readOnly = true)
    public ReorderSuggestion reorder(
            UUID userId,
            UUID productId,
            LocalDate asOf,
            Integer leadTimeDays,
            BigDecimal inbound
    ) {
        if (leadTimeDays == null) {
            return new ReorderSuggestion(null, "lead time");
        }
        if (inbound == null) {
            return new ReorderSuggestion(null, "inbound stock");
        }
        Forecast forecast = forecast(userId, productId, asOf);
        if (forecast.forecastQuantity() == null) {
            return new ReorderSuggestion(null, "history");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        Window window = window(businessId, productId, asOf);
        BigDecimal demand = scale(window.gross(), leadTimeDays);
        BigDecimal onHand = onHand(businessId, productId);
        BigDecimal suggestion = demand.subtract(onHand).subtract(inbound);
        if (suggestion.signum() < 0) {
            suggestion = BigDecimal.ZERO;
        }
        return new ReorderSuggestion(suggestion, null);
    }

    private Window window(UUID businessId, UUID productId, LocalDate asOf) {
        LocalDate start = asOf.minusDays(WINDOW_DAYS - 1L);
        int covered = 0;
        boolean complete = true;
        for (LocalDate day = start; !day.isAfter(asOf); day = day.plusDays(1)) {
            if (coverage.existsByBusinessIdAndCoveredOn(businessId, day)) {
                covered = covered + 1;
            } else {
                complete = false;
            }
        }
        Instant from = start.atStartOfDay(KOLKATA).toInstant();
        Instant until = asOf.plusDays(1).atStartOfDay(KOLKATA).toInstant();
        Map<LocalDate, BigDecimal> sales = new HashMap<>();
        BigDecimal returns = BigDecimal.ZERO;
        for (StockMovement movement : movements.salesAndReturnsBetween(businessId, productId, from, until)) {
            LocalDate day = ZonedDateTime.ofInstant(movement.getOccurredAt(), KOLKATA).toLocalDate();
            if (movement.getReason() == StockReason.SALE) {
                BigDecimal sold = movement.getSignedBaseQuantity().negate();
                sales.merge(day, sold, BigDecimal::add);
            } else if (movement.getReason() == StockReason.RETURN) {
                returns = returns.add(movement.getSignedBaseQuantity());
            }
        }
        BigDecimal gross = sales.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int positive = 0;
        for (BigDecimal quantity : sales.values()) {
            if (quantity.signum() > 0) {
                positive = positive + 1;
            }
        }
        boolean eligible = complete && covered >= WINDOW_DAYS && positive >= MINIMUM_POSITIVE_DAYS;
        return new Window(eligible, gross, returns, covered, positive);
    }

    private BigDecimal onHand(UUID businessId, UUID productId) {
        return balances.findByBusinessIdAndProductIdAndOwnershipAndBatchIdIsNull(businessId, productId, Ownership.BUSINESS)
                .stream()
                .map(StockBalance::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal scale(BigDecimal gross, int days) {
        return gross.multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(WINDOW_DAYS), 6, RoundingMode.HALF_UP);
    }

    private record Window(
            boolean eligible,
            BigDecimal gross,
            BigDecimal returns,
            int coveredDays,
            int positiveDays
    ) {
    }
}
