package in.munimsaathi.location;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Names;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.catalog.ProductRepository;
import in.munimsaathi.catalog.QuantityConversion;
import in.munimsaathi.inventory.Ownership;
import in.munimsaathi.inventory.PostedOperation;
import in.munimsaathi.inventory.PostedOperationRepository;
import in.munimsaathi.inventory.SignedStockChange;
import in.munimsaathi.inventory.StockBalance;
import in.munimsaathi.inventory.StockBalanceRepository;
import in.munimsaathi.inventory.StockMovementRepository;
import in.munimsaathi.inventory.StockPosting;
import in.munimsaathi.inventory.StockReason;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class LocationBook {

    private final TenantContext tenants;
    private final CatalogService catalog;
    private final ProductRepository products;
    private final GodownRepository godowns;
    private final PostedOperationRepository operations;
    private final LocationFactRepository facts;
    private final StockPosting stock;
    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;

    public LocationBook(
            TenantContext tenants,
            CatalogService catalog,
            ProductRepository products,
            GodownRepository godowns,
            PostedOperationRepository operations,
            LocationFactRepository facts,
            StockPosting stock,
            StockBalanceRepository balances,
            StockMovementRepository movements
    ) {
        this.tenants = tenants;
        this.catalog = catalog;
        this.products = products;
        this.godowns = godowns;
        this.operations = operations;
        this.facts = facts;
        this.stock = stock;
        this.balances = balances;
        this.movements = movements;
    }

    @Transactional
    public UUID remember(UUID userId, UUID operationId, String label, UUID locationId, UUID productId, Instant notedAt) {
        UUID businessId = tenants.currentBusinessId(userId);
        var prior = facts.findByOperationId(operationId);
        if (prior.isPresent()) {
            if (!prior.get().getBusinessId().equals(businessId)) {
                throw new IllegalArgumentException("Unknown operation.");
            }
            return prior.get().getId();
        }
        if (operationId == null || notedAt == null) {
            throw new IllegalArgumentException("A location note needs an operation and a time.");
        }
        if (operations.existsById(operationId)) {
            throw new IllegalArgumentException("This operation was already used.");
        }
        requireGodown(businessId, locationId);
        if (productId != null) {
            requireProduct(businessId, productId);
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "LOCATION", userId));
        return facts.save(new LocationFact(
                UUID.randomUUID(),
                businessId,
                Names.normalize(label),
                productId,
                locationId,
                Ownership.UNKNOWN,
                null,
                notedAt,
                userId,
                operationId
        )).getId();
    }

    @Transactional
    public UUID receiveCustomerOwned(
            UUID userId,
            UUID operationId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            String label,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        var prior = facts.findByOperationId(operationId);
        if (prior.isPresent()) {
            if (!prior.get().getBusinessId().equals(businessId)) {
                throw new IllegalArgumentException("Unknown operation.");
            }
            return prior.get().getId();
        }
        if (operations.existsById(operationId)) {
            throw new IllegalArgumentException("This operation was already used.");
        }
        requireGodown(businessId, locationId);
        requireProduct(businessId, productId);
        if (occurredAt == null) {
            throw new IllegalArgumentException("A business date and a time are required.");
        }
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "CUSTOMER_STOCK", userId));
        stock.add(change(
                businessId,
                productId,
                locationId,
                conversion,
                conversion.baseQuantity(),
                StockReason.RECEIPT,
                Ownership.CUSTOMER,
                operationId,
                occurredAt
        ));
        return facts.save(new LocationFact(
                UUID.randomUUID(),
                businessId,
                Names.normalize(label),
                productId,
                locationId,
                Ownership.CUSTOMER,
                conversion.baseQuantity(),
                occurredAt,
                userId,
                operationId
        )).getId();
    }

    @Transactional
    public void transfer(
            UUID userId,
            UUID operationId,
            UUID productId,
            UUID fromLocationId,
            UUID toLocationId,
            BigDecimal displayQuantity,
            String unitLabel,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        if (operations.existsById(operationId)) {
            return;
        }
        if (fromLocationId.equals(toLocationId)) {
            throw new IllegalArgumentException("Choose a different godown.");
        }
        requireGodown(businessId, fromLocationId);
        requireGodown(businessId, toLocationId);
        requireProduct(businessId, productId);
        if (occurredAt == null) {
            throw new IllegalArgumentException("A business date and a time are required.");
        }
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "TRANSFER", userId));
        stock.transfer(
                change(businessId, productId, fromLocationId, conversion, conversion.baseQuantity().negate(),
                        StockReason.TRANSFER, Ownership.BUSINESS, operationId, occurredAt),
                change(businessId, productId, toLocationId, conversion, conversion.baseQuantity(),
                        StockReason.TRANSFER, Ownership.BUSINESS, operationId, occurredAt)
        );
    }

    @Transactional(readOnly = true)
    public List<Place> locate(UUID userId, UUID productId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireProduct(businessId, productId);
        List<Place> stocked = new ArrayList<>();
        for (StockBalance balance : balances.findByBusinessIdAndProductIdAndOwnershipAndBatchIdIsNull(
                businessId, productId, Ownership.BUSINESS)) {
            if (balance.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                stocked.add(new Place(balance.getLocationId(), balance.getQuantity(), true));
            }
        }
        stocked.sort(Comparator.comparing(Place::locationId));
        if (!stocked.isEmpty()) {
            return stocked;
        }
        var lastMovement = movements.findFirstByBusinessIdAndProductIdAndOwnershipOrderByOccurredAtDesc(
                businessId, productId, Ownership.BUSINESS);
        if (lastMovement.isPresent()) {
            return List.of(new Place(lastMovement.get().getLocationId(), BigDecimal.ZERO, false));
        }
        return facts.findFirstByBusinessIdAndProductIdOrderByNotedAtDesc(businessId, productId)
                .map(fact -> List.of(new Place(fact.getLocationId(), BigDecimal.ZERO, false)))
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public BigDecimal businessQuantity(UUID userId, UUID productId, UUID locationId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return stock.businessOwnedQuantity(businessId, productId, locationId);
    }

    @Transactional(readOnly = true)
    public BigDecimal customerQuantity(UUID userId, UUID productId, UUID locationId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return stock.ownedQuantity(businessId, productId, locationId, Ownership.CUSTOMER);
    }

    private void requireGodown(UUID businessId, UUID locationId) {
        Godown godown = godowns.findById(locationId).orElseThrow(() -> new IllegalArgumentException("Unknown godown."));
        if (!godown.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown godown.");
        }
    }

    private Product requireProduct(UUID businessId, UUID productId) {
        Product product = products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Unknown product."));
        if (!product.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown product.");
        }
        return product;
    }

    private static SignedStockChange change(
            UUID businessId,
            UUID productId,
            UUID locationId,
            QuantityConversion conversion,
            BigDecimal signedBase,
            StockReason reason,
            Ownership ownership,
            UUID operationId,
            Instant occurredAt
    ) {
        return new SignedStockChange(
                businessId,
                productId,
                null,
                locationId,
                signedBase,
                conversion.displayQuantity(),
                conversion.unitLabel(),
                conversion.factorToBase(),
                reason,
                operationId,
                occurredAt,
                ownership
        );
    }
}
