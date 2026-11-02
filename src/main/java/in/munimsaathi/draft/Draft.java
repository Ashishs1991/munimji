package in.munimsaathi.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "drafts")
public class Draft {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftStatus status;

    @Column(nullable = false)
    private long version;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "display_quantity", precision = 18, scale = 6)
    private BigDecimal displayQuantity;

    @Column(name = "unit_label")
    private String unitLabel;

    @Column(name = "amount_paise")
    private Long amountPaise;

    @Column(name = "occurred_on")
    private LocalDate occurredOn;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "missing_fields", nullable = false)
    private String missingFields;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "presented_version")
    private Long presentedVersion;

    @Column(name = "presented_hash")
    private String presentedHash;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "allocates_document_id")
    private UUID allocatesDocumentId;

    @Column
    private String cause;

    @Column
    private String direction;

    protected Draft() {
    }

    public Draft(UUID id, UUID businessId, UUID actorId) {
        this.id = id;
        this.businessId = businessId;
        this.actorId = actorId;
        this.version = 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public DraftStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getDisplayQuantity() {
        return displayQuantity;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public String getKind() {
        return kind;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getMissingFields() {
        return missingFields;
    }

    public Long getPresentedVersion() {
        return presentedVersion;
    }

    public String getPresentedHash() {
        return presentedHash;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public UUID getAllocatesDocumentId() {
        return allocatesDocumentId;
    }

    public String getCause() {
        return cause;
    }

    public String getDirection() {
        return direction;
    }

    public void present() {
        this.presentedVersion = this.version;
        this.presentedHash = this.contentHash;
    }

    void rememberMoney(String paymentMethod, UUID allocatesDocumentId, String cause, String direction) {
        this.paymentMethod = paymentMethod;
        this.allocatesDocumentId = allocatesDocumentId;
        this.cause = cause;
        this.direction = direction;
    }

    void suspend() {
        this.status = DraftStatus.SUSPENDED;
    }

    void resume(DraftStatus status) {
        this.status = status;
    }

    void cancel() {
        this.status = DraftStatus.CANCELED;
    }

    void replace(
            String kind,
            DraftStatus status,
            String contentHash,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt,
            String missingFields,
            Instant expiresAt
    ) {
        this.version = this.version + 1;
        this.kind = kind;
        this.status = status;
        this.contentHash = contentHash;
        this.customerId = customerId;
        this.productId = productId;
        this.locationId = locationId;
        this.displayQuantity = displayQuantity;
        this.unitLabel = unitLabel;
        this.amountPaise = amountPaise;
        this.occurredOn = occurredOn;
        this.occurredAt = occurredAt;
        this.missingFields = missingFields;
        this.expiresAt = expiresAt;
    }

    void markPosted() {
        this.status = DraftStatus.POSTED;
    }
}
