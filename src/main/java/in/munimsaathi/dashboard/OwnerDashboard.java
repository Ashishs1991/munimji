package in.munimsaathi.dashboard;

import in.munimsaathi.catalog.Product;
import in.munimsaathi.catalog.ProductRepository;
import in.munimsaathi.draft.Draft;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.forecast.Forecast;
import in.munimsaathi.forecast.ForecastService;
import in.munimsaathi.imports.ImportBatchRepository;
import in.munimsaathi.inventory.Ownership;
import in.munimsaathi.inventory.StockMovementRepository;
import in.munimsaathi.inventory.StockSlice;
import in.munimsaathi.ledger.AccountSide;
import in.munimsaathi.ledger.DocumentKind;
import in.munimsaathi.ledger.FinanceStatus;
import in.munimsaathi.ledger.PartyLedgerRepository;
import in.munimsaathi.ledger.PostedDocument;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.reminder.ReminderKind;
import in.munimsaathi.reminder.ReminderStatus;
import in.munimsaathi.reminder.ReminderTask;
import in.munimsaathi.reminder.ReminderTaskRepository;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessAccessDeniedException;
import in.munimsaathi.tenant.BusinessContextRepository;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OwnerDashboard {

    private final TenantContext tenants;
    private final MembershipRepository memberships;
    private final BusinessContextRepository contexts;
    private final BusinessRepository businesses;
    private final GodownRepository godowns;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final PartyLedgerRepository ledger;
    private final PostedDocumentRepository documents;
    private final DraftRepository drafts;
    private final StockThresholdRepository thresholds;
    private final LowStockAlertRepository alerts;
    private final ReminderTaskRepository reminders;
    private final ImportBatchRepository imports;
    private final ForecastService forecasts;

    public OwnerDashboard(
            TenantContext tenants,
            MembershipRepository memberships,
            BusinessContextRepository contexts,
            BusinessRepository businesses,
            GodownRepository godowns,
            ProductRepository products,
            StockMovementRepository movements,
            PartyLedgerRepository ledger,
            PostedDocumentRepository documents,
            DraftRepository drafts,
            StockThresholdRepository thresholds,
            LowStockAlertRepository alerts,
            ReminderTaskRepository reminders,
            ImportBatchRepository imports,
            ForecastService forecasts
    ) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.contexts = contexts;
        this.businesses = businesses;
        this.godowns = godowns;
        this.products = products;
        this.movements = movements;
        this.ledger = ledger;
        this.documents = documents;
        this.drafts = drafts;
        this.thresholds = thresholds;
        this.alerts = alerts;
        this.reminders = reminders;
        this.imports = imports;
        this.forecasts = forecasts;
    }

    @Transactional
    public void setThreshold(UUID userId, UUID productId, BigDecimal minimumBaseQuantity) {
        if (minimumBaseQuantity == null || minimumBaseQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("A stock threshold cannot be negative.");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        Product product = products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Unknown product."));
        if (!product.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown product.");
        }
        StockThreshold threshold = thresholds.findByBusinessIdAndProductId(businessId, productId)
                .orElseGet(() -> new StockThreshold(UUID.randomUUID(), businessId, productId, minimumBaseQuantity));
        threshold.change(minimumBaseQuantity);
        thresholds.save(threshold);
    }

    @Transactional
    public Overview overview(UUID userId, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Choose the date these records are for.");
        }
        List<Membership> owned = memberships.findByAuthUserId(userId);
        if (owned.isEmpty()) {
            throw new BusinessAccessDeniedException();
        }
        UUID selected = contexts.findById(userId).map(context -> context.getBusinessId()).orElse(null);
        if (selected != null && owned.stream().noneMatch(membership -> membership.getBusinessId().equals(selected))) {
            throw new BusinessAccessDeniedException();
        }
        if (selected == null && owned.size() != 1) {
            return empty(true, null, date, choices(userId, null));
        }
        UUID businessId = selected == null ? owned.get(0).getBusinessId() : selected;
        Business business = businesses.findById(businessId).orElseThrow(BusinessAccessDeniedException::new);
        Instant until = date.plusDays(1).atStartOfDay(ZoneId.of(business.getTimezone())).toInstant();
        List<StockSlice> slices = movements.summedUntil(businessId, Ownership.BUSINESS, until);
        Map<UUID, BigDecimal> onHand = new HashMap<>();
        List<Overview.StockRow> stock = new ArrayList<>();
        for (StockSlice slice : slices) {
            if (slice.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            onHand.merge(slice.getProductId(), slice.getQuantity(), BigDecimal::add);
            Product product = products.findById(slice.getProductId()).orElseThrow();
            Godown godown = godowns.findById(slice.getLocationId()).orElseThrow();
            stock.add(new Overview.StockRow(
                    product.getId(),
                    product.getName(),
                    godown.getName(),
                    slice.getQuantity(),
                    plain(slice.getQuantity())
            ));
        }
        stock.sort(Comparator.comparing(Overview.StockRow::godownName).thenComparing(Overview.StockRow::productName));
        List<String> unknownExpiry = unknownExpiry(businessId, until);
        boolean historical = date.isBefore(LocalDate.now(ZoneId.of(business.getTimezone())));
        List<LowStockAlert> openAlerts = historical
                ? alerts.findByBusinessIdAndResolvedAtIsNull(businessId)
                : refreshAlerts(businessId, date, onHand);
        List<PostedDocument> today = documents.findByBusinessIdAndOccurredOn(businessId, date);
        List<String> forecastLines = forecastLines(userId, businessId, date);
        return new Overview(
                false,
                business.getName(),
                date,
                choices(userId, businessId),
                List.copyOf(stock),
                unknownExpiry,
                ledger.summedPaise(businessId, AccountSide.RECEIVABLE, until),
                ledger.summedPaise(businessId, AccountSide.PAYABLE, until),
                sum(today, DocumentKind.SALE),
                sum(today, DocumentKind.CREDIT),
                sum(today, DocumentKind.RECEIPT),
                ids(today, DocumentKind.SALE),
                ids(today, DocumentKind.CREDIT),
                ids(today, DocumentKind.RECEIPT),
                countDrafts(businessId, date, DraftStatus.NEEDS_CLARIFICATION),
                countDrafts(businessId, date, DraftStatus.AWAITING_APPROVAL),
                (int) documents.countByBusinessIdAndFinanceStatusAndOccurredOnLessThanEqual(
                        businessId, FinanceStatus.PENDING, date
                ),
                openAlerts.stream().map(alert -> new Overview.AlertRow(
                        products.findById(alert.getProductId()).orElseThrow().getName(),
                        plain(alert.getObservedQuantity()),
                        plain(alert.getThresholdQuantity())
                )).toList(),
                forecastLines.stream().anyMatch(line -> !line.endsWith("insufficient history")),
                ownerReminders(businessId),
                openDrafts(businessId),
                importLines(businessId),
                forecastLines
        );
    }

    private List<LowStockAlert> refreshAlerts(UUID businessId, LocalDate date, Map<UUID, BigDecimal> onHand) {
        List<LowStockAlert> open = new ArrayList<>();
        for (StockThreshold threshold : thresholds.findByBusinessId(businessId)) {
            BigDecimal quantity = onHand.getOrDefault(threshold.getProductId(), BigDecimal.ZERO);
            LowStockAlert alert = alerts.findByBusinessIdAndProductIdAndResolvedAtIsNull(businessId, threshold.getProductId())
                    .orElse(null);
            if (quantity.compareTo(threshold.getMinimumBaseQuantity()) <= 0) {
                if (alert == null) {
                    alert = new LowStockAlert(
                            UUID.randomUUID(),
                            businessId,
                            threshold.getProductId(),
                            threshold.getMinimumBaseQuantity(),
                            quantity,
                            date
                    );
                    alerts.save(alert);
                } else {
                    alert.observe(threshold.getMinimumBaseQuantity(), quantity);
                }
                open.add(alert);
            } else if (alert != null) {
                alert.resolve(Instant.now());
            }
        }
        return open;
    }

    private List<String> unknownExpiry(UUID businessId, Instant until) {
        List<String> names = new ArrayList<>();
        for (StockSlice slice : movements.summedUnknownExpiryUntil(businessId, Ownership.BUSINESS, until)) {
            if (slice.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                names.add(products.findById(slice.getProductId()).orElseThrow().getName());
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names.stream().distinct().toList());
    }

    private int countDrafts(UUID businessId, LocalDate date, DraftStatus status) {
        int count = 0;
        for (Draft draft : drafts.findByBusinessIdAndStatus(businessId, status)) {
            if (draft.getOccurredOn() == null || !draft.getOccurredOn().isAfter(date)) {
                count++;
            }
        }
        return count;
    }

    private List<Overview.BusinessChoice> choices(UUID userId, UUID selected) {
        List<Overview.BusinessChoice> choices = new ArrayList<>();
        for (Membership membership : memberships.findByAuthUserId(userId)) {
            Business business = businesses.findById(membership.getBusinessId()).orElseThrow();
            choices.add(new Overview.BusinessChoice(
                    business.getId(),
                    business.getName(),
                    business.getId().equals(selected)
            ));
        }
        choices.sort(Comparator.comparing(Overview.BusinessChoice::name));
        return List.copyOf(choices);
    }

    private static Overview empty(boolean selectionRequired, String name, LocalDate date, List<Overview.BusinessChoice> choices) {
        return new Overview(
                selectionRequired,
                name,
                date,
                choices,
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private List<String> ownerReminders(UUID businessId) {
        return reminders.findByBusinessIdAndStatusIn(businessId, List.of(
                ReminderStatus.AWAITING_APPROVAL,
                ReminderStatus.NEEDS_REAPPROVAL,
                ReminderStatus.SCHEDULED,
                ReminderStatus.DASHBOARD
        )).stream()
                .filter(task -> task.getKind() == ReminderKind.OWNER_REMINDER)
                .map(OwnerDashboard::reminderText)
                .toList();
    }

    private List<String> openDrafts(UUID businessId) {
        return drafts.findByBusinessIdAndStatusIn(
                businessId,
                List.of(DraftStatus.NEEDS_CLARIFICATION, DraftStatus.AWAITING_APPROVAL)
        ).stream().map(draft -> {
            String missing = draft.getMissingFields();
            if (missing == null || missing.isBlank()) {
                return draft.getStatus().name();
            }
            return draft.getStatus().name() + " missing " + missing;
        }).toList();
    }

    private List<String> importLines(UUID businessId) {
        return imports.findByBusinessId(businessId).stream()
                .map(batch -> batch.getStatus().name() + " " + batch.getFileName())
                .toList();
    }

    private List<String> forecastLines(UUID userId, UUID businessId, LocalDate date) {
        List<String> lines = new ArrayList<>();
        for (Product product : products.findByBusinessId(businessId)) {
            Forecast forecast = forecasts.forecast(userId, product.getId(), date);
            if (forecast.forecastQuantity() == null) {
                lines.add(product.getName() + ": insufficient history");
            } else {
                lines.add(product.getName() + ": " + forecast.forecastQuantity().toPlainString()
                        + " over " + forecast.horizonDays() + " days");
            }
        }
        return lines;
    }

    private static String reminderText(ReminderTask task) {
        String note = task.getApprovedPayload() == null ? "owner reminder" : task.getApprovedPayload();
        return task.getStatus() + " " + task.getLocalDate() + " " + note;
    }

    private static long sum(List<PostedDocument> documents, DocumentKind kind) {
        long total = 0;
        for (PostedDocument document : documents) {
            if (document.getKind() == kind && document.getTotalPaise() != null) {
                total = Math.addExact(total, document.getTotalPaise());
            }
        }
        return total;
    }

    private static List<UUID> ids(List<PostedDocument> documents, DocumentKind kind) {
        List<UUID> ids = new ArrayList<>();
        for (PostedDocument document : documents) {
            if (document.getKind() == kind) {
                ids.add(document.getId());
            }
        }
        return List.copyOf(ids);
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
