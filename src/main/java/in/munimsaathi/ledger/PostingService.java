package in.munimsaathi.ledger;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Names;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyRepository;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.QuantityConversion;
import in.munimsaathi.inventory.Ownership;
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
import java.util.ArrayList;
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
    private final InvoiceCounterRepository invoiceCounters;
    private final InvoiceNumberRepository invoiceNumbers;
    private final CorrectionRepository corrections;
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
            InvoiceCounterRepository invoiceCounters,
            InvoiceNumberRepository invoiceNumbers,
            CorrectionRepository corrections,
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
        this.invoiceCounters = invoiceCounters;
        this.invoiceNumbers = invoiceNumbers;
        this.corrections = corrections;
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
        issueInvoiceNumber(businessId, document.getId());
        return document.getId();
    }

    @Transactional
    public UUID dispatch(
            UUID userId,
            UUID operationId,
            UUID customerId,
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
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "DISPATCH", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.SALE,
                occurredOn,
                null,
                operationId,
                null,
                FinanceStatus.PENDING
        ));
        lines.save(line(document.getId(), productId, locationId, conversion, null, false));
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
        return document.getId();
    }

    @Transactional
    public UUID sellLines(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID locationId,
            List<SaleLine> saleLines,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        if (saleLines == null || saleLines.isEmpty()) {
            throw new IllegalArgumentException("A sale needs at least one line.");
        }
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        List<QuantityConversion> conversions = new ArrayList<>();
        boolean priced = true;
        long total = 0;
        for (SaleLine saleLine : saleLines) {
            conversions.add(catalog.convert(
                    userId, saleLine.productId(), saleLine.displayQuantity(), saleLine.unitLabel()
            ));
            if (saleLine.amountPaise() == null) {
                priced = false;
            } else {
                requirePositive(saleLine.amountPaise());
                total = Math.addExact(total, saleLine.amountPaise());
            }
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, priced ? "SALE" : "DISPATCH", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.SALE,
                occurredOn,
                priced ? total : null,
                operationId,
                null,
                priced ? FinanceStatus.RECORDED : FinanceStatus.PENDING
        ));
        for (int index = 0; index < saleLines.size(); index++) {
            SaleLine saleLine = saleLines.get(index);
            lines.save(line(
                    document.getId(),
                    saleLine.productId(),
                    locationId,
                    conversions.get(index),
                    saleLine.amountPaise(),
                    false
            ));
            stock.add(change(
                    businessId,
                    saleLine.productId(),
                    locationId,
                    conversions.get(index),
                    conversions.get(index).baseQuantity().negate(),
                    StockReason.SALE,
                    operationId,
                    occurredAt
            ));
        }
        if (priced) {
            ledger.save(new PartyLedgerEntry(
                    UUID.randomUUID(),
                    businessId,
                    customerId,
                    AccountSide.RECEIVABLE,
                    total,
                    LedgerReason.SALE,
                    document.getId(),
                    operationId,
                    occurredAt
            ));
            issueInvoiceNumber(businessId, document.getId());
        }
        return document.getId();
    }

    @Transactional
    public UUID receivePayment(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID saleDocumentId,
            long amountPaise,
            String paymentMethod,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        String method = requirePaymentMethod(paymentMethod);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        PostedDocument sale = requireSale(businessId, customerId, saleDocumentId);
        requireWithinOutstanding(sale, amountPaise);
        return postReceipt(userId, businessId, operationId, customerId, sale.getId(), amountPaise, method, occurredOn, occurredAt);
    }

    @Transactional
    public UUID receiveUnallocated(
            UUID userId,
            UUID operationId,
            UUID customerId,
            long amountPaise,
            String paymentMethod,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        String method = requirePaymentMethod(paymentMethod);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        return postReceipt(userId, businessId, operationId, customerId, null, amountPaise, method, occurredOn, occurredAt);
    }

    @Transactional
    public void cancelInvoice(UUID userId, UUID operationId, UUID saleDocumentId) {
        UUID businessId = tenants.currentBusinessId(userId);
        if (operationId == null) {
            throw new IllegalArgumentException("An operation id is required.");
        }
        if (operations.existsById(operationId)) {
            return;
        }
        PostedDocument sale = documents.findById(saleDocumentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sale."));
        if (!sale.getBusinessId().equals(businessId) || sale.getKind() != DocumentKind.SALE) {
            throw new IllegalArgumentException("Unknown sale.");
        }
        InvoiceNumber invoiceNumber = invoiceNumbers.findByDocumentId(sale.getId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown invoice."));
        if (!invoiceNumber.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown invoice.");
        }
        if (invoiceNumber.getStatus() == InvoiceNumberStatus.CANCELED) {
            throw new IllegalArgumentException("This invoice number is already canceled.");
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "INVOICE_CANCEL", userId));
        invoiceNumber.cancel();
    }

    @Transactional(readOnly = true)
    public long unappliedReceiptPaise(UUID userId, UUID partyId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireParty(businessId, partyId, PartyRole.CUSTOMER);
        long unapplied = 0;
        List<PartyLedgerEntry> receipts = ledger.findByBusinessIdAndPartyIdAndAccountSideAndReason(
                businessId,
                partyId,
                AccountSide.RECEIVABLE,
                LedgerReason.RECEIPT
        );
        for (PartyLedgerEntry entry : receipts) {
            long paid = Math.negateExact(entry.getSignedPaise());
            long applied = allocations.findBySourceEntryId(entry.getId()).map(Allocation::getAmountPaise).orElse(0L);
            unapplied = Paise.add(unapplied, Paise.subtract(paid, applied));
        }
        return unapplied;
    }

    @Transactional
    public void approveSupplierBill(UUID userId, UUID operationId, UUID billId, Instant occurredAt) {
        UUID businessId = tenants.currentBusinessId(userId);
        if (operationId == null) {
            throw new IllegalArgumentException("An operation id is required.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("A business date and a time are required.");
        }
        Optional<PostedOperation> existing = operations.findById(operationId);
        if (existing.isPresent()) {
            if (!"SUPPLIER_BILL_APPROVAL".equals(existing.get().getKind())) {
                throw new IllegalArgumentException("This operation was already used.");
            }
            return;
        }
        PostedDocument bill = requireSupplierBill(businessId, billId);
        if (ledger.existsBySourceDocumentIdAndAccountSideAndReason(bill.getId(), AccountSide.PAYABLE, LedgerReason.PURCHASE)) {
            throw new IllegalArgumentException("This bill is already on the supplier balance.");
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "SUPPLIER_BILL_APPROVAL", userId));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                bill.getPartyId(),
                AccountSide.PAYABLE,
                bill.getTotalPaise(),
                LedgerReason.PURCHASE,
                bill.getId(),
                operationId,
                occurredAt
        ));
    }

    @Transactional
    public UUID paySupplier(
            UUID userId,
            UUID operationId,
            UUID supplierId,
            UUID billId,
            long amountPaise,
            String paymentMethod,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        String method = requirePaymentMethod(paymentMethod);
        requireParty(businessId, supplierId, PartyRole.SUPPLIER);
        requireWhen(occurredOn, occurredAt);
        if (amountPaise > ledger.payablePaise(businessId, supplierId)) {
            throw new IllegalArgumentException("Payment is larger than the supplier balance.");
        }
        if (billId != null) {
            PostedDocument bill = requireSupplierBill(businessId, billId);
            if (!bill.getPartyId().equals(supplierId)) {
                throw new IllegalArgumentException("Unknown bill.");
            }
            long outstanding = Paise.subtract(bill.getTotalPaise(), allocations.allocatedTo(bill.getId()));
            if (amountPaise > outstanding) {
                throw new IllegalArgumentException("Amount is larger than what is still owed on this bill.");
            }
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "SUPPLIER_PAYMENT", userId));
        PostedDocument payment = new PostedDocument(
                UUID.randomUUID(),
                businessId,
                supplierId,
                DocumentKind.SUPPLIER_PAYMENT,
                occurredOn,
                amountPaise,
                operationId,
                billId,
                FinanceStatus.RECORDED
        );
        payment.recordPaymentMethod(method);
        documents.saveAndFlush(payment);
        PartyLedgerEntry entry = ledger.saveAndFlush(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                supplierId,
                AccountSide.PAYABLE,
                Math.negateExact(amountPaise),
                LedgerReason.PAYMENT,
                payment.getId(),
                operationId,
                occurredAt
        ));
        if (billId != null) {
            allocations.save(new Allocation(
                    UUID.randomUUID(),
                    businessId,
                    entry.getId(),
                    billId,
                    AccountSide.PAYABLE,
                    amountPaise
            ));
        }
        return payment.getId();
    }

    @Transactional(readOnly = true)
    public long supplierPayablePaise(UUID userId, UUID partyId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireParty(businessId, partyId, PartyRole.SUPPLIER);
        return ledger.payablePaise(businessId, partyId);
    }

    @Transactional(readOnly = true)
    public long unappliedSupplierPaymentPaise(UUID userId, UUID partyId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireParty(businessId, partyId, PartyRole.SUPPLIER);
        long unapplied = 0;
        List<PartyLedgerEntry> payments = ledger.findByBusinessIdAndPartyIdAndAccountSideAndReason(
                businessId,
                partyId,
                AccountSide.PAYABLE,
                LedgerReason.PAYMENT
        );
        for (PartyLedgerEntry entry : payments) {
            long paid = Math.negateExact(entry.getSignedPaise());
            long applied = allocations.findBySourceEntryId(entry.getId()).map(Allocation::getAmountPaise).orElse(0L);
            unapplied = Paise.add(unapplied, Paise.subtract(paid, applied));
        }
        return unapplied;
    }

    private PostedDocument requireSupplierBill(UUID businessId, UUID billId) {
        PostedDocument bill = documents.lockById(billId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bill."));
        if (!bill.getBusinessId().equals(businessId)
                || bill.getKind() != DocumentKind.SUPPLIER_BILL
                || bill.getTotalPaise() == null) {
            throw new IllegalArgumentException("Unknown bill.");
        }
        return bill;
    }

    private UUID postReceipt(
            UUID userId,
            UUID businessId,
            UUID operationId,
            UUID customerId,
            UUID saleDocumentId,
            long amountPaise,
            String paymentMethod,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "CUSTOMER_RECEIPT", userId));
        PostedDocument document = new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.RECEIPT,
                occurredOn,
                amountPaise,
                operationId,
                saleDocumentId,
                FinanceStatus.RECORDED
        );
        document.recordPaymentMethod(paymentMethod);
        documents.saveAndFlush(document);
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
        if (saleDocumentId != null) {
            allocations.save(new Allocation(
                    UUID.randomUUID(),
                    businessId,
                    entry.getId(),
                    saleDocumentId,
                    AccountSide.RECEIVABLE,
                    amountPaise
            ));
        }
        return document.getId();
    }

    private void issueInvoiceNumber(UUID businessId, UUID documentId) {
        InvoiceCounter counter = invoiceCounters.lock(businessId).orElseGet(() -> {
            invoiceCounters.saveAndFlush(new InvoiceCounter(businessId));
            return invoiceCounters.lock(businessId).orElseThrow();
        });
        invoiceNumbers.save(new InvoiceNumber(UUID.randomUUID(), businessId, counter.issueNext(), documentId));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A correction needs a reason.");
        }
        return reason.trim();
    }

    private static String requirePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("A payment method is required.");
        }
        return paymentMethod.trim();
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
            String reason,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(creditPaise);
        String recordedReason = requireReason(reason);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireGodown(businessId, locationId);
        requireWhen(occurredOn, occurredAt);
        PostedDocument sale = requireSale(businessId, customerId, saleDocumentId);
        QuantityConversion conversion = catalog.convert(userId, productId, displayQuantity, unitLabel);
        requireWithinSold(sale.getId(), productId, conversion.baseQuantity());
        long alreadyCredited = documents.linkedPaise(sale.getId(), DocumentKind.CREDIT);
        if (creditPaise > Paise.subtract(sale.getTotalPaise(), alreadyCredited)) {
            throw new IllegalArgumentException("Credit is larger than the sale.");
        }
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
        corrections.save(new Correction(
                UUID.randomUUID(),
                businessId,
                operationId,
                sale.getId(),
                document.getId(),
                recordedReason,
                userId
        ));
        return document.getId();
    }

    @Transactional
    public UUID refundCash(
            UUID userId,
            UUID operationId,
            UUID customerId,
            UUID creditDocumentId,
            long amountPaise,
            String reason,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        String recordedReason = requireReason(reason);
        requireParty(businessId, customerId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        PostedDocument credit = documents.findById(creditDocumentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown credit."));
        if (!credit.getBusinessId().equals(businessId)
                || credit.getKind() != DocumentKind.CREDIT
                || !customerId.equals(credit.getPartyId())
                || credit.getTotalPaise() == null) {
            throw new IllegalArgumentException("Unknown credit.");
        }
        long alreadyRefunded = documents.linkedPaise(credit.getId(), DocumentKind.REFUND);
        if (amountPaise > Paise.subtract(credit.getTotalPaise(), alreadyRefunded)) {
            throw new IllegalArgumentException("Refund is larger than the credit.");
        }
        long balance = ledger.receivablePaise(businessId, customerId);
        if (balance >= 0 || amountPaise > Math.negateExact(balance)) {
            throw new IllegalArgumentException("There is no credit balance to refund.");
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "REFUND", userId));
        PostedDocument refund = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                customerId,
                DocumentKind.REFUND,
                occurredOn,
                amountPaise,
                operationId,
                credit.getId(),
                FinanceStatus.RECORDED
        ));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                customerId,
                AccountSide.RECEIVABLE,
                amountPaise,
                LedgerReason.REFUND,
                refund.getId(),
                operationId,
                occurredAt
        ));
        corrections.save(new Correction(
                UUID.randomUUID(),
                businessId,
                operationId,
                credit.getId(),
                refund.getId(),
                recordedReason,
                userId
        ));
        return refund.getId();
    }

    @Transactional
    public UUID recordOpeningReceivable(
            UUID userId,
            UUID operationId,
            UUID partyId,
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
        requireParty(businessId, partyId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "OPENING_RECEIVABLE", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                partyId,
                DocumentKind.OPENING,
                occurredOn,
                amountPaise,
                operationId,
                null,
                FinanceStatus.RECORDED
        ));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                partyId,
                AccountSide.RECEIVABLE,
                amountPaise,
                LedgerReason.OPENING,
                document.getId(),
                operationId,
                occurredAt
        ));
        return document.getId();
    }

    @Transactional
    public UUID recordStandaloneReceivable(
            UUID userId,
            UUID operationId,
            UUID partyId,
            long amountPaise,
            String cause,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        requireParty(businessId, partyId, PartyRole.CUSTOMER);
        requireWhen(occurredOn, occurredAt);
        if (cause == null || cause.isBlank()) {
            throw new IllegalArgumentException("Say whether this receivable is new.");
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "STANDALONE_RECEIVABLE", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                partyId,
                DocumentKind.LOAN,
                occurredOn,
                amountPaise,
                operationId,
                null,
                FinanceStatus.RECORDED
        ));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                partyId,
                AccountSide.RECEIVABLE,
                amountPaise,
                LedgerReason.LOAN,
                document.getId(),
                operationId,
                occurredAt
        ));
        return document.getId();
    }

    @Transactional
    public UUID recordSupplierObligation(
            UUID userId,
            UUID operationId,
            UUID partyId,
            long amountPaise,
            String cause,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Optional<UUID> prior = alreadyPosted(businessId, operationId);
        if (prior.isPresent()) {
            return prior.get();
        }
        requirePositive(amountPaise);
        requireParty(businessId, partyId, PartyRole.SUPPLIER);
        requireWhen(occurredOn, occurredAt);
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "SUPPLIER_OBLIGATION", userId));
        PostedDocument document = documents.saveAndFlush(new PostedDocument(
                UUID.randomUUID(),
                businessId,
                partyId,
                DocumentKind.PAYABLE_NOTE,
                occurredOn,
                amountPaise,
                operationId,
                null,
                FinanceStatus.RECORDED
        ));
        ledger.save(new PartyLedgerEntry(
                UUID.randomUUID(),
                businessId,
                partyId,
                AccountSide.PAYABLE,
                amountPaise,
                LedgerReason.OBLIGATION,
                document.getId(),
                operationId,
                occurredAt
        ));
        return document.getId();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> soleOpenSale(UUID userId, UUID customerId, long amountPaise) {
        UUID businessId = tenants.currentBusinessId(userId);
        List<PostedDocument> open = documents.findByBusinessIdAndPartyIdAndKind(businessId, customerId, DocumentKind.SALE).stream()
                .filter(sale -> sale.getTotalPaise() != null)
                .filter(sale -> Paise.subtract(sale.getTotalPaise(), allocations.allocatedTo(sale.getId())) >= amountPaise)
                .toList();
        return open.size() == 1 ? Optional.of(open.get(0).getId()) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public long customerReceivablePaise(UUID userId, UUID partyId) {
        return customerReceivablePaiseForBusiness(tenants.currentBusinessId(userId), partyId);
    }

    @Transactional(readOnly = true)
    public long customerReceivablePaiseForBusiness(UUID businessId, UUID partyId) {
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
        PostedDocument sale = documents.lockById(saleDocumentId)
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
                occurredAt,
                Ownership.BUSINESS
        );
    }
}
