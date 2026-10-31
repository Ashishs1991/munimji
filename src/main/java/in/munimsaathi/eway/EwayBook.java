package in.munimsaathi.eway;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.catalog.ProductRepository;
import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.ledger.DocumentKind;
import in.munimsaathi.ledger.DocumentLine;
import in.munimsaathi.ledger.DocumentLineRepository;
import in.munimsaathi.ledger.InvoiceNumber;
import in.munimsaathi.ledger.InvoiceNumberRepository;
import in.munimsaathi.ledger.PostedDocument;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class EwayBook {

    private static final DateTimeFormatter DOC_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TenantContext tenants;
    private final EwayDraftRepository drafts;
    private final PostedDocumentRepository documents;
    private final DocumentLineRepository lines;
    private final InvoiceNumberRepository invoiceNumbers;
    private final ProductRepository products;
    private final EwayGateway gateway;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final MunimProperties properties;

    public EwayBook(
            TenantContext tenants,
            EwayDraftRepository drafts,
            PostedDocumentRepository documents,
            DocumentLineRepository lines,
            InvoiceNumberRepository invoiceNumbers,
            ProductRepository products,
            EwayGateway gateway,
            ObjectMapper mapper,
            TransactionTemplate transactions,
            MunimProperties properties
    ) {
        this.tenants = tenants;
        this.drafts = drafts;
        this.documents = documents;
        this.lines = lines;
        this.invoiceNumbers = invoiceNumbers;
        this.products = products;
        this.gateway = gateway;
        this.mapper = mapper;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Transactional
    public UUID prepare(UUID userId, UUID documentId, EwayDetails details) {
        UUID businessId = tenants.currentBusinessId(userId);
        PostedDocument document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("This invoice is not in the selected business."));
        if (!document.getBusinessId().equals(businessId) || document.getKind() != DocumentKind.SALE) {
            throw new IllegalArgumentException("This invoice is not in the selected business.");
        }
        EwayDraft draft = drafts.findByBusinessIdAndDocumentId(businessId, documentId).orElse(null);
        if (draft != null && (draft.getStatus() == EwayStatus.CONFIRMED
                || draft.getStatus() == EwayStatus.SUBMITTING
                || draft.getStatus() == EwayStatus.OUTCOME_UNKNOWN)) {
            return draft.getId();
        }
        if (draft == null) {
            draft = drafts.save(new EwayDraft(
                    UUID.randomUUID(),
                    businessId,
                    documentId,
                    userId,
                    environment(),
                    UUID.randomUUID()
            ));
        }
        fill(draft, details, document);
        return draft.getId();
    }

    @Transactional
    public UUID prepareNumber(UUID userId, long invoiceNumber, EwayDetails details) {
        UUID businessId = tenants.currentBusinessId(userId);
        InvoiceNumber invoice = invoiceNumbers.findByBusinessIdAndNumber(businessId, invoiceNumber)
                .orElseThrow(() -> new IllegalArgumentException("This invoice is not in the selected business."));
        return prepare(userId, invoice.getDocumentId(), details);
    }

    @Transactional
    public void approve(UUID userId, UUID draftId) {
        EwayDraft draft = require(userId, draftId);
        if (draft.getStatus() == EwayStatus.VALIDATION_BLOCKED) {
            throw new IllegalArgumentException("Ask for the missing e-way fields.");
        }
        if (draft.getStatus() != EwayStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        draft.queue();
    }

    public EwayDraftView submit(UUID userId, UUID draftId) {
        Decision decision = transactions.execute(status -> decide(userId, draftId));
        if (decision == null || decision.kind == Kind.DONE) {
            return view(userId, draftId);
        }
        EwayReply reply;
        boolean lookup = decision.kind == Kind.LOOKUP;
        try {
            reply = lookup
                    ? gateway.lookup(decision.docType, decision.docNo)
                    : gateway.generate(decision.payload);
        } catch (RuntimeException exception) {
            reply = lookup
                    ? EwayReply.unknown("The provider lookup did not finish.")
                    : EwayReply.unknownSent("The provider did not answer.");
        }
        EwayReply recorded = reply;
        transactions.executeWithoutResult(status -> apply(draftId, recorded, lookup));
        return view(userId, draftId);
    }

    public void cancel(UUID userId, UUID draftId) {
        require(userId, draftId);
        throw new IllegalStateException(
                "Cancellation is unsupported. Ask the authorized operator. The official e-way bill is unchanged."
        );
    }

    @Transactional(readOnly = true)
    public EwayDraftView view(UUID userId, UUID draftId) {
        EwayDraft draft = require(userId, draftId);
        return new EwayDraftView(
                draft.getId(),
                draft.getDocumentId(),
                draft.getStatus().name(),
                draft.getEnvironment(),
                draft.getVersion(),
                draft.getEwayBillNo(),
                draft.getEwayBillDate(),
                draft.getValidUpto(),
                draft.getErrorText(),
                label(draft, false)
        );
    }

    @Transactional(readOnly = true)
    public String label(UUID userId, UUID draftId, boolean localPdf) {
        return label(require(userId, draftId), localPdf);
    }

    private Decision decide(UUID userId, UUID draftId) {
        EwayDraft draft = require(userId, draftId);
        if (draft.getStatus() == EwayStatus.VALIDATION_BLOCKED) {
            throw new IllegalArgumentException("Ask for the missing e-way fields.");
        }
        if (draft.getStatus() == EwayStatus.AWAITING_APPROVAL || draft.getStatus() == EwayStatus.REJECTED) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        if (draft.getStatus() == EwayStatus.CONFIRMED) {
            return Decision.done();
        }
        if (draft.getStatus() == EwayStatus.SUBMITTING || draft.getStatus() == EwayStatus.OUTCOME_UNKNOWN) {
            return Decision.lookup("INV", String.valueOf(draft.getInvoiceNumber()));
        }
        if (draft.getStatus() != EwayStatus.QUEUED || !draft.approvalMatches()) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        PostedDocument document = documents.findById(draft.getDocumentId()).orElseThrow();
        InvoiceNumber invoice = invoiceNumbers.findByDocumentId(document.getId()).orElse(null);
        Long number = invoice == null ? null : invoice.getNumber();
        if (!java.util.Objects.equals(draft.getInvoiceNumber(), number)
                || !java.util.Objects.equals(draft.getInvoiceTotalPaise(), document.getTotalPaise())
                || !java.util.Objects.equals(draft.getInvoiceDate(), document.getOccurredOn())) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        if (!configured()) {
            throw new IllegalStateException("E-way submission is not configured.");
        }
        draft.markSubmitting();
        return Decision.generate(draft.getPayload());
    }

    private void apply(UUID draftId, EwayReply reply, boolean lookup) {
        EwayDraft draft = drafts.findById(draftId).orElseThrow();
        if (official(reply)) {
            draft.confirm(reply.ewayBillNo(), reply.ewayBillDate(), reply.validUpto(), reply.status());
            return;
        }
        if (!lookup && !reply.billRequestSent() && "0".equals(reply.status())) {
            draft.requeue(reply.error());
            return;
        }
        if (!lookup && reply.billRequestSent() && "0".equals(reply.status())) {
            draft.reject(reply.error() == null ? "The e-way portal rejected the draft." : reply.error());
            return;
        }
        draft.unknown(reply.error() == null ? "The provider outcome is unknown." : reply.error(), reply.status());
    }

    private void fill(EwayDraft draft, EwayDetails details, PostedDocument document) {
        InvoiceNumber invoice = invoiceNumbers.findByDocumentId(document.getId()).orElse(null);
        List<DocumentLine> documentLines = lines.findByDocumentId(document.getId());
        Product product = null;
        DocumentLine line = null;
        if (documentLines.size() == 1) {
            line = documentLines.get(0);
            product = products.findById(line.getProductId()).orElse(null);
            if (product != null && !product.getBusinessId().equals(document.getBusinessId())) {
                product = null;
                line = null;
            }
        }
        List<String> missing = gaps(details, document, invoice, line, product);
        Long invoiceNumber = invoice == null ? null : invoice.getNumber();
        String payload = payload(details, document, invoiceNumber, line, product);
        draft.replace(
                payload,
                sha256(payload),
                missing.isEmpty() ? null : String.join(", ", missing),
                missing.isEmpty(),
                invoiceNumber,
                document.getTotalPaise(),
                document.getOccurredOn()
        );
    }

    private List<String> gaps(
            EwayDetails details,
            PostedDocument document,
            InvoiceNumber invoice,
            DocumentLine line,
            Product product
    ) {
        List<String> missing = new ArrayList<>();
        if (invoice == null) {
            missing.add("invoice number");
        }
        if (document.getTotalPaise() == null) {
            missing.add("invoice total");
        }
        if (line == null || product == null) {
            missing.add("invoice line");
        }
        if (details == null) {
            missing.add("e-way details");
            return missing;
        }
        if (!"O".equals(details.supplyType()) && !"I".equals(details.supplyType())) {
            missing.add("supply type");
        }
        if (blank(details.subSupplyType())) {
            missing.add("sub supply type");
        }
        partyGaps(details.from(), "from", missing);
        partyGaps(details.to(), "to", missing);
        if (details.from() != null && !blank(details.from().gstin()) && !gstin(details.from().gstin())) {
            missing.add("from GSTIN");
        } else if (details.from() != null && gstin(details.from().gstin()) && !blank(configuredGstin())
                && !configuredGstin().equals(details.from().gstin())) {
            missing.add("from GSTIN does not match the configured business");
        }
        if (details.to() != null && !blank(details.to().gstin()) && !"URP".equals(details.to().gstin())
                && !gstin(details.to().gstin())) {
            missing.add("to GSTIN");
        }
        if (details.transactionType() == null || details.transactionType() < 1 || details.transactionType() > 4) {
            missing.add("transaction type");
        }
        if (details.distance() == null || !details.distance().matches("\\d{1,4}")) {
            missing.add("distance");
        }
        if (details.transMode() == null || !details.transMode().matches("[1-4]")) {
            missing.add("transport mode");
        }
        if (blank(details.vehicleNo()) && blank(details.transporterId())) {
            missing.add("vehicle or transporter");
        }
        if (details.hsnCode() == null || details.hsnCode() < 1000 || details.hsnCode() > 99_999_999L) {
            missing.add("HSN");
        }
        if (blank(details.qtyUnit())) {
            missing.add("unit");
        }
        taxGaps(details.tax(), document.getTotalPaise(), missing);
        return missing;
    }

    private void partyGaps(EwayParty party, String side, List<String> missing) {
        if (party == null) {
            missing.add(side + " GSTIN");
            missing.add(side + " name");
            missing.add(side + " address");
            missing.add(side + " place");
            missing.add(side + " pincode");
            missing.add(side + " state");
            missing.add(side + " actual state");
            return;
        }
        if (blank(party.gstin())) {
            missing.add(side + " GSTIN");
        }
        if (blank(party.name())) {
            missing.add(side + " name");
        }
        if (blank(party.address())) {
            missing.add(side + " address");
        }
        if (blank(party.place())) {
            missing.add(side + " place");
        }
        if (!pincode(party.pincode())) {
            missing.add(side + " pincode");
        }
        if (!state(party.stateCode())) {
            missing.add(side + " state");
        }
        if (!state(party.actualStateCode())) {
            missing.add(side + " actual state");
        }
    }

    private void taxGaps(EwayTax tax, Long invoiceTotal, List<String> missing) {
        if (tax == null
                || tax.taxablePaise() == null
                || tax.cgstPaise() == null
                || tax.sgstPaise() == null
                || tax.igstPaise() == null
                || tax.cessPaise() == null) {
            missing.add("tax");
            return;
        }
        if (tax.taxablePaise() < 0 || tax.cgstPaise() < 0 || tax.sgstPaise() < 0 || tax.igstPaise() < 0 || tax.cessPaise() < 0) {
            missing.add("tax");
            return;
        }
        if (!rate(tax.cgstRate()) || !rate(tax.sgstRate()) || !rate(tax.igstRate()) || !rate(tax.cessRate())) {
            missing.add("tax rate");
            return;
        }
        long sum;
        try {
            sum = Math.addExact(
                    tax.taxablePaise(),
                    Math.addExact(
                            tax.cgstPaise(),
                            Math.addExact(tax.sgstPaise(), Math.addExact(tax.igstPaise(), tax.cessPaise()))
                    )
            );
        } catch (ArithmeticException exception) {
            missing.add("tax");
            return;
        }
        if (invoiceTotal == null || sum != invoiceTotal) {
            missing.add("tax does not match the invoice");
        }
    }

    private String payload(
            EwayDetails details,
            PostedDocument document,
            Long invoiceNumber,
            DocumentLine line,
            Product product
    ) {
        EwayDetails stated = details == null
                ? new EwayDetails(null, null, null, null, null, null, null, null, null, null, null, null)
                : details;
        EwayTax tax = stated.tax() == null
                ? new EwayTax(null, null, null, null, null, null, null, null, null)
                : stated.tax();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productName", product == null ? null : product.getName());
        item.put("productDesc", product == null ? null : product.getName());
        item.put("hsnCode", stated.hsnCode());
        item.put("quantity", line == null ? null : line.getDisplayQuantity());
        item.put("qtyUnit", stated.qtyUnit());
        item.put("taxableAmount", rupees(tax.taxablePaise()));
        item.put("cgstRate", tax.cgstRate());
        item.put("sgstRate", tax.sgstRate());
        item.put("igstRate", tax.igstRate());
        item.put("cessRate", tax.cessRate());
        item.put("cgstAmount", rupees(tax.cgstPaise()));
        item.put("sgstAmount", rupees(tax.sgstPaise()));
        item.put("igstAmount", rupees(tax.igstPaise()));
        item.put("cessAmount", rupees(tax.cessPaise()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplyType", stated.supplyType());
        body.put("subSupplyType", stated.subSupplyType());
        body.put("docType", "INV");
        body.put("docNo", invoiceNumber == null ? null : Long.toString(invoiceNumber));
        body.put("docDate", document.getOccurredOn() == null ? null : DOC_DATE.format(document.getOccurredOn()));
        putParty(body, "from", stated.from());
        putParty(body, "to", stated.to());
        body.put("transactionType", stated.transactionType());
        body.put("transDistance", stated.distance());
        body.put("transMode", stated.transMode());
        body.put("vehicleNo", blank(stated.vehicleNo()) ? null : stated.vehicleNo());
        body.put("transporterId", blank(stated.transporterId()) ? null : stated.transporterId());
        body.put("totInvValue", rupees(document.getTotalPaise()));
        body.put("itemList", List.of(item));
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalArgumentException("The e-way draft could not be saved.", exception);
        }
    }

    private void putParty(Map<String, Object> body, String side, EwayParty party) {
        String prefix = side.equals("from") ? "from" : "to";
        String actual = side.equals("from") ? "actFromStateCode" : "actToStateCode";
        body.put(prefix + "Gstin", party == null ? null : party.gstin());
        body.put(prefix + "TrdName", party == null ? null : party.name());
        body.put(prefix + "Addr1", party == null ? null : party.address());
        body.put(prefix + "Place", party == null ? null : party.place());
        body.put(prefix + "Pincode", party == null ? null : party.pincode());
        body.put(prefix + "StateCode", party == null ? null : party.stateCode());
        body.put(actual, party == null ? null : party.actualStateCode());
    }

    private String label(EwayDraft draft, boolean localPdf) {
        boolean official = draft.getStatus() == EwayStatus.CONFIRMED
                && !blank(draft.getEwayBillNo())
                && !blank(draft.getEwayBillDate())
                && !blank(draft.getValidUpto());
        if (localPdf && !official) {
            return "local copy";
        }
        if (localPdf) {
            return draft.getEnvironment() + " " + draft.getEwayBillNo() + " valid " + draft.getValidUpto();
        }
        if (official) {
            return draft.getEnvironment() + " generated " + draft.getEwayBillNo() + " valid " + draft.getValidUpto();
        }
        return draft.getEnvironment() + " " + draft.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private EwayDraft require(UUID userId, UUID draftId) {
        UUID businessId = tenants.currentBusinessId(userId);
        EwayDraft draft = drafts.findById(draftId).orElseThrow(
                () -> new IllegalArgumentException("This e-way draft is not in the selected business.")
        );
        if (!draft.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("This e-way draft is not in the selected business.");
        }
        return draft;
    }

    private String environment() {
        MunimProperties.Eway eway = properties.eway();
        if (eway == null || blank(eway.environment())) {
            return "sandbox";
        }
        String value = eway.environment().trim().toLowerCase(Locale.ROOT);
        if (!"sandbox".equals(value) && !"production".equals(value)) {
            throw new IllegalArgumentException("The e-way environment must be sandbox or production.");
        }
        return value;
    }

    private boolean configured() {
        MunimProperties.Eway eway = properties.eway();
        return eway != null
                && eway.enabled()
                && ("sandbox".equals(eway.environment()) || "production".equals(eway.environment()))
                && !blank(eway.baseUrl())
                && !blank(eway.clientId())
                && !blank(eway.clientSecret())
                && !blank(eway.gstin())
                && !blank(eway.username())
                && !blank(eway.password());
    }

    private String configuredGstin() {
        MunimProperties.Eway eway = properties.eway();
        return eway == null ? null : eway.gstin();
    }

    private static boolean official(EwayReply reply) {
        return reply != null
                && "1".equals(reply.status())
                && !blank(reply.ewayBillNo())
                && !blank(reply.ewayBillDate())
                && !blank(reply.validUpto());
    }

    private static BigDecimal rupees(Long paise) {
        return paise == null ? null : BigDecimal.valueOf(paise, 2);
    }

    private static boolean gstin(String value) {
        return value != null && value.matches("[0-9]{2}[A-Z0-9]{13}");
    }

    private static boolean pincode(Integer value) {
        return value != null && value >= 100_000 && value <= 999_999;
    }

    private static boolean state(Integer value) {
        return value != null && value >= 1 && value <= 99;
    }

    private static boolean rate(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(new BigDecimal("100")) <= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("The e-way draft could not be saved.", exception);
        }
    }

    private enum Kind {
        DONE,
        GENERATE,
        LOOKUP
    }

    private record Decision(Kind kind, String payload, String docType, String docNo) {
        static Decision done() {
            return new Decision(Kind.DONE, null, null, null);
        }

        static Decision generate(String payload) {
            return new Decision(Kind.GENERATE, payload, null, null);
        }

        static Decision lookup(String docType, String docNo) {
            return new Decision(Kind.LOOKUP, null, docType, docNo);
        }
    }
}
