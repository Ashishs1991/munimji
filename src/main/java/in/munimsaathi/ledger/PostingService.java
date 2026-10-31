package in.munimsaathi.ledger;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Names;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyRepository;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.QuantityConversion;
import in.munimsaathi.inventory.PostedOperation;
import in.munimsaathi.inventory.PostedOperationRepository;
import in.munimsaathi.inventory.SignedStockChange;
import in.munimsaathi.inventory.StockPosting;
import in.munimsaathi.inventory.StockReason;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PostingService {

    private final TenantContext tenants;
    private final CatalogService catalog;
    private final PartyRepository parties;
    private final GodownRepository godowns;
    private final PostedOperationRepository operations;
    private final PostedDocumentRepository documents;
    private final DocumentLineRepository lines;
    private final PartyLedgerRepository ledger;
    private final AllocationRepository allocations;
    private final BillReviewRepository reviews;
    private final StockPosting stock;

    public PostingService(
            TenantContext tenants,
            CatalogService catalog,
            PartyRepository parties,
            GodownRepository godowns,
            PostedOperationRepository operations,
            PostedDocumentRepository documents,
            DocumentLineRepository lines,
            PartyLedgerRepository ledger,
            AllocationRepository allocations,
            BillReviewRepository reviews,
            StockPosting stock
    ) {
        this.tenants = tenants;
        this.catalog = catalog;
        this.parties = parties;
        this.godowns = godowns;
        this.operations = operations;
        this.documents = documents;
        this.lines = lines;
        this.ledger = ledger;
        this.allocations = allocations;
        this.reviews = reviews;
        this.stock = stock;
    }

    @Transactional
    public UUID receiveGoods(
            UUID userId,
            UUID operationId,
            UUID supplierId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requireParty(businessId, supplierId, PartyRole.SUPPLIER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "PURCHASE_RECEIPT", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                supplierId,
                DocumentKind.PURCHASE,
                occurredOn,
                null,
                operationId,
                null,
                FinanceStatus.PENDING
        ));
        lines.save(line(document.getId(), productId, locationId, conversion, null, false));
        stock.add(change(businessId, productId, locationId, conversion, conversion.baseQuantity(), StockReason.RECEIPT, operationId, occurredAt));
        return document.getId();
    }

    @Transactional
    public PurchaseBillOutcome recordSupplierBill(
            UUID userId,
            UUID operationId,
            UUID receiptId,
            String billNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            UUID productId,
            BigDecimal displayQuantity,
            String unitLabel,
            long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return new PurchaseBillOutcome(prior.get(), null);
        }
        requirePositive(amountPaise);
        requireWhen(occurredOn, occurredAt);
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("A bill needs a period that ends on or after it starts.");
        }
        PostedDocument receipt = requireReceipt(businessId, receiptId);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        String number = Names.normalize(billNumber);
        String fingerprint = BillFingerprint.of(
                receipt.getPartyId(),
                periodStart,
                periodEnd,
                productId,
                conversion.baseQuantity(),
                amountPaise
        );
        if (documents.existsByBusinessIdAndPartyIdAndBillNumberAndPeriodStartAndPeriodEnd(
                businessId, receipt.getPartyId(), number, periodStart, periodEnd)
                || documents.existsByBusinessIdAndFingerprint(businessId, fingerprint)) {
            BillReview review = reviews.save(new BillReview(
                    UUID.randomUUID(),
                    businessId,
                    receipt.getPartyId(),
                    number,
                    periodStart,
                    periodEnd,
                    fingerprint
            ));
            return new PurchaseBillOutcome(null, review.getId());
        }
        requireWithinReceived(receipt.getId(), productId, conversion.baseQuantity());
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "SUPPLIER_BILL", userId));
        PostedDocument bill = new PostedDocument(
                UUID.randomUUID(),
                businessId,
                receipt.getPartyId(),
                DocumentKind.SUPPLIER_BILL,
                occurredOn,
                amountPaise,
                operationId,
                receipt.getId(),
                FinanceStatus.RECORDED
        );
        bill.describeBill(number, periodStart, periodEnd, fingerprint);
        documents.saveAndFlush(bill);
        lines.saveAndFlush(line(bill.getId(), productId, receiptLocation(receipt.getId()), conversion, amountPaise, false));
        BigDecimal billed = sum(lines.billedBaseQuantities(receipt.getId(), productId));
        BigDecimal received = sum(lines.baseQuantities(receipt.getId(), productId));
        receipt.markFinance(billed.compareTo(received) == 0 ? FinanceStatus.BILLED : FinanceStatus.PARTIAL);
        return new PurchaseBillOutcome(bill.getId(), null);
    }

    @Transactional
    public UUID sell(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "SALE", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.SALE,
                occurredOn,
                amountPaise,
                operationId,
                null,
                FinanceStatus.RECORDED
        ));
        lines.save(line(document.getId(), productId, locationId, conversion, amountPaise, false));
        stock.add(change(
                businessId,
                productId,
                locationId,
                conversion,
                conversion.baseQuantity().negate(),
                StockReason.SALE,
                operationId,
                occurredAt
        ));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                customerId,
                AccountSide.RECEIVABLE,
                amountPaise,
                LedgerReason.SALE,
                document.getId(),
                operationId,
                occurredAt
        ));
        return document.getId();
    }

    @Transactional
    public UUID receivePayment(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID saleDocumentId,
            long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        PostedDocument sale = requireSale(businessId, customerId, saleDocumentId);
        requireWithinOutstanding(sale, amountPaise);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "CUSTOMER_RECEIPT", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.RECEIPT,
                occurredOn,
                amountPaise,
                operationId,
                sale.getId(),
                FinanceStatus.RECORDED
        ));
        PartyLedgerEntry entry = ledger.saveAndFlush(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                customerId,
                AccountSide.RECEIVABLE,
                Math.negateExact(amountPaise),
                LedgerReason.RECEIPT,
                document.getId(),
                operationId,
                occurredAt
        ));
        allocations.save(new Allocation(
                UUID.randomUUID(),
                businessId,
                entry.getId(),
                sale.getId(),
                AccountSide.RECEIVABLE,
                amountPaise
        ));
        return document.getId();
    }

    @Transactional
    public UUID acceptReturn(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID saleDocumentId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            long creditPaise,
            boolean restock,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(creditPaise);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        PostedDocument sale = requireSale(businessId, customerId, saleDocumentId);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        requireWithinSold(sale.getId(), productId, conversion.baseQuantity());
        requireWithinOutstanding(sale, creditPaise);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "RETURN", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.CREDIT,
                occurredOn,
                creditPaise,
                operationId,
                sale.getId(),
                FinanceStatus.RECORDED
        ));
        lines.save(line(document.getId(), productId, locationId, conversion, creditPaise, restock));
        if (restock) {
            stock.add(change(
                    businessId,
                    productId,
                    locationId,
                    conversion,
                    conversion.baseQuantity(),
                    StockReason.RETURN,
                    operationId,
                    occurredAt
            ));
        }
        PartyLedgerEntry entry = ledger.saveAndFlush(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                customerId,
                AccountSide.RECEIVABLE,
                Math.negateExact(creditPaise),
                LedgerReason.CREDIT,
                document.getId(),
                operationId,
                occurredAt
        ));
        allocations.save(new Allocation(
                UUID.randomUUID(),
                businessId,
                entry.getId(),
                sale.getId(),
                AccountSide.RECEIVABLE,
                creditPaise
        ));
        return document.getId();
    }

    @Transactional(readOnly = true)
    public long customerReceivablePaise(UUID userId, UUID partyId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireParty(businessId, partyId, PartyRole.CUSTOMER);
        return ledger.receivablePaise(businessId, partyId);
    }

    private Optional<UUID> alreadyPosted(UUID businessId, UUID operationId) {
        if (operationId == null) {
            throw new IllegalArgumentException("An operation id is required.");
        }
        Optional<PostedDocument> existing = documents.findByOperationId(operationId);
        if (existing.isPresent()) {
            if (!existing.get().getBusinessId().equals(businessId)) {
                throw new IllegalArgumentException("Unknown operation.");
            }
            return Optional.of(existing.get().getId());
        }
        if (operations.existsById(operationId)) {
            throw new IllegalArgumentException("This operation was already used.");
        }
        return Optional.empty();
    }

    private Party requireParty(UUID businessId, UUID partyId, PartyRole required) {
        Party party = parties.findById(partyId).orElseThrow(() -> new IllegalArgumentException("Unknown party."));
        if (!party.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown party.");
        }
        if (party.getRole() != required && party.getRole() != PartyRole.BOTH) {
            throw new IllegalArgumentException("Unknown party.");
        }
        return party;
    }

    private void requireGodown(UUID businessId, UUID locationId) {
        Godown godown = godowns.findById(locationId).orElseThrow(() -> new IllegalArgumentException("Unknown godown."));
        if (!godown.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown godown.");
        }
    }

    private PostedDocument requireReceipt(UUID businessId, UUID receiptId) {
        PostedDocument receipt = documents.findById(receiptId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown receipt."));
        if (!receipt.getBusinessId().equals(businessId) || receipt.getKind() != DocumentKind.PURCHASE) {
            throw new IllegalArgumentException("Unknown receipt.");
        }
        return receipt;
    }

    private UUID receiptLocation(UUID receiptId) {
        List<DocumentLine> receiptLines = lines.findByDocumentId(receiptId);
        if (receiptLines.isEmpty()) {
            throw new IllegalArgumentException("Unknown receipt.");
        }
        return receiptLines.get(0).getLocationId();
    }

    private void requireWithinReceived(UUID receiptId, UUID productId, BigDecimal baseQuantity) {
        BigDecimal received = sum(lines.baseQuantities(receiptId, productId));
        BigDecimal alreadyBilled = sum(lines.billedBaseQuantities(receiptId, productId));
        if (received.signum() == 0 || alreadyBilled.add(baseQuantity).compareTo(received) > 0) {
            throw new IllegalArgumentException("Bill quantity is larger than the goods received.");
        }
    }

    private PostedDocument requireSale(UUID businessId, UUID customerId, UUID saleDocumentId) {
        PostedDocument sale = documents.findById(saleDocumentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sale."));
        if (!sale.getBusinessId().equals(businessId)
                || sale.getKind() != DocumentKind.SALE
                || !customerId.equals(sale.getPartyId())
                || sale.getTotalPaise() == null) {
            throw new IllegalArgumentException("Unknown sale.");
        }
        return sale;
    }

    private void requireWithinOutstanding(PostedDocument sale, long paise) {
        long outstanding = Paise.subtract(sale.getTotalPaise(), allocations.allocatedTo(sale.getId()));
        if (paise > outstanding) {
            throw new IllegalArgumentException("Amount is larger than what is still owed on this sale.");
        }
    }

    private void requireWithinSold(UUID saleId, UUID productId, BigDecimal baseQuantity) {
        BigDecimal sold = sum(lines.baseQuantities(saleId, productId));
        BigDecimal alreadyReturned = sum(lines.returnedBaseQuantities(saleId, productId));
        if (sold.signum() == 0 || alreadyReturned.add(baseQuantity).compareTo(sold) > 0) {
            throw new IllegalArgumentException("Return quantity is larger than the sale.");
        }
    }

    private static BigDecimal sum(List<BigDecimal> quantities) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal quantity : quantities) {
            total = total.add(quantity);
        }
        return total;
    }

    private static void requirePositive(long paise) {
        if (paise <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
    }

    private static void requireWhen(LocalDate occurredOn, Instant occurredAt) {
        if (occurredOn == null || occurredAt == null) {
            throw new IllegalArgumentException("A business date and a time are required.");
        }
    }

    private static DocumentLine line(
            UUID documentId,
            UUID productId,
            UUID locationId,
            QuantityConversion conversion,
            Long amountPaise,
            boolean restock
    ) {
        return new DocumentLine(
                UUID.randomUUID(),
                documentId,
                productId,
                locationId,
                conversion.displayQuantity(),
                conversion.unitLabel(),
                conversion.factorToBase(),
                conversion.baseQuantity(),
                amountPaise,
                restock
        );
    }

    private static SignedStockChange change(
            UUID businessId,
            UUID productId,
            UUID locationId,
            QuantityConversion conversion,
            BigDecimal signedBase,
            StockReason reason,
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
                occurredAt
        );
    }
}
