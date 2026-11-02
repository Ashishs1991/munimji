package in.munimsaathi.interpretation;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.Draft;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.AccountSide;
import in.munimsaathi.ledger.DocumentKind;
import in.munimsaathi.ledger.LedgerReason;
import in.munimsaathi.ledger.PartyLedgerRepository;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import in.munimsaathi.tenant.TenantContext;
import in.munimsaathi.voice.VoiceReceipts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class DemoRecoveryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final Instant AT = Instant.parse("2026-08-01T04:30:00Z");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @MockBean
    private LanguageModel model;

    @MockBean
    private Transcriber transcriber;

    @Autowired
    private CommandInterpreter interpreter;

    @Autowired
    private DraftBook drafts;

    @Autowired
    private DraftRepository draftRows;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private GodownRepository godowns;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Autowired
    private CountService counts;

    @Autowired
    private PostingService posting;

    @Autowired
    private PostedDocumentRepository documents;

    @Autowired
    private PartyLedgerRepository ledger;

    @Autowired
    private TenantContext tenants;

    @Autowired
    private VoiceReceipts voices;

    @Test
    void aPendingTwoThousandSaleStaysUntouchedWhenAPaymentArrives() {
        Shop shop = shop();
        parties.create(shop.userId(), "Rahul", PartyRole.CUSTOMER);
        when(model.complete("do box bech do")).thenReturn("""
                {"intent":"sale","party_name":"Rahul","product_name":"Office folder","quantity":"2","unit":"box","amount_paise":200000,"location_code":"g1","requested_tools":[]}
                """);
        InterpretationOutcome sale = interpreter.interpret(shop.userId(), "do box bech do", DAY, AT);
        when(model.complete("Rahul ke 1000 received")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","intent":"payment_received","party_name":"Rahul","amount_paise":100000,"requested_tools":[]}
                """);

        InterpretationOutcome payment = interpreter.interpret(shop.userId(), "Rahul ke 1000 received", sale.draft().id(), DAY, AT);

        Draft storedSale = draftRows.findById(sale.draft().id()).orElseThrow();
        Draft storedPayment = draftRows.findById(payment.draft().id()).orElseThrow();
        assertThat(storedPayment.getId()).isNotEqualTo(storedSale.getId());
        assertThat(storedSale.getKind()).isEqualTo("SALE");
        assertThat(storedSale.getAmountPaise()).isEqualTo(200_000L);
        assertThat(storedSale.getDisplayQuantity()).isEqualByComparingTo("2");
        assertThat(storedSale.getProductId()).isEqualTo(shop.productId());
        assertThat(storedSale.getLocationId()).isEqualTo(shop.godownId());
        assertThat(storedPayment.getKind()).isEqualTo("PAYMENT");
        assertThat(storedPayment.getAmountPaise()).isEqualTo(100_000L);
        assertThat(storedPayment.getProductId()).isNull();
        assertThat(storedPayment.getLocationId()).isNull();
        assertThat(storedPayment.getDisplayQuantity()).isNull();
        assertThat(storedPayment.getMissingFields()).doesNotContain("product").doesNotContain("location").doesNotContain("quantity");
    }

    @Test
    void anAllocatedReceiptLeavesTheRemainingReceivableAndDoesNotChangeStock() {
        Shop shop = shop();
        openBoxes(shop, "5");
        UUID rahul = parties.create(shop.userId(), "Rahul", PartyRole.CUSTOMER).getId();
        when(model.complete("sell")).thenReturn("""
                {"intent":"sale","party_name":"Rahul","product_name":"Office folder","quantity":"1","unit":"box","amount_paise":150000,"location_code":"g1","requested_tools":[]}
                """);
        InterpretationOutcome sale = interpreter.interpret(shop.userId(), "sell", DAY, AT);
        drafts.approve(shop.userId(), sale.draft().id(), sale.draft().version(), sale.draft().contentHash());
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        when(model.complete("Rahul ke 1000 received")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"Cash","requested_tools":[]}
                """);

        InterpretationOutcome payment = interpreter.interpret(shop.userId(), "Rahul ke 1000 received", DAY, AT);
        UUID posted = drafts.approve(shop.userId(), payment.draft().id(), payment.draft().version(), payment.draft().contentHash());
        UUID replay = drafts.approve(shop.userId(), payment.draft().id(), payment.draft().version(), payment.draft().contentHash());

        assertThat(replay).isEqualTo(posted);
        assertThat(posting.customerReceivablePaise(shop.userId(), rahul)).isEqualTo(50_000L);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(documents.findByOperationId(payment.draft().id())).isPresent();
    }

    @Test
    void anUnknownPayerIsCreatedWithoutAskingForGoods() {
        Shop shop = shop();
        when(model.complete("Rahul ke 1000 received")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"requested_tools":[]}
                """);
        InterpretationOutcome asked = interpreter.interpret(shop.userId(), "Rahul ke 1000 received", DAY, AT);
        assertThat(asked.answer()).contains("create");
        assertThat(asked.draft().missingFields()).contains("party").doesNotContain("product", "location", "quantity");

        when(model.complete("create Rahul")).thenReturn("""
                {"turn":"answer_to_clarification","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"UPI","create_party":true,"requested_tools":[]}
                """);
        InterpretationOutcome payment = interpreter.interpret(shop.userId(), "create Rahul", asked.draft().id(), DAY, AT);

        Draft stored = draftRows.findById(payment.draft().id()).orElseThrow();
        assertThat(stored.getId()).isEqualTo(asked.draft().id());
        assertThat(stored.getProductId()).isNull();
        assertThat(stored.getLocationId()).isNull();
        assertThat(stored.getMissingFields()).doesNotContain("product").doesNotContain("location").doesNotContain("quantity");
        assertThat(stored.getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        drafts.approve(shop.userId(), payment.draft().id(), payment.draft().version(), payment.draft().contentHash());
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("0");
    }

    @Test
    void anExistingReceivableIsNotRecordedAgain() {
        Shop shop = shop();
        posting.recordOpeningReceivable(shop.userId(), UUID.randomUUID(), shop.customerId(), 100_000L, DAY, AT);
        when(model.complete("Agarwal se 1000 lene hain")).thenReturn("""
                {"action":"standalone_receivable","party_name":"Agarwal","amount_paise":100000,"cause":"existing","requested_tools":[]}
                """);

        InterpretationOutcome outcome = interpreter.interpret(shop.userId(), "Agarwal se 1000 lene hain", DAY, AT);

        assertThat(outcome.draft()).isNull();
        assertThat(outcome.answer()).contains("already recorded");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(100_000L);
        assertThat(draftRows.findAll().stream().filter(draft -> shop.businessId().equals(draft.getBusinessId())).toList()).isEmpty();
    }

    @Test
    void aNewLoanPostsAReceivableAndASupplierNotePostsAPayable() {
        Shop shop = shop();
        when(model.complete("Maine Agarwal ko 1000 udhaar diye")).thenReturn("""
                {"turn":"new_action","action":"standalone_receivable","party_name":"Agarwal","amount_paise":100000,"cause":"new","requested_tools":[]}
                """);
        InterpretationOutcome loan = interpreter.interpret(shop.userId(), "Maine Agarwal ko 1000 udhaar diye", DAY, AT);
        UUID loanDocument = drafts.approve(shop.userId(), loan.draft().id(), loan.draft().version(), loan.draft().contentHash());

        assertThat(documents.findById(loanDocument).orElseThrow().getKind()).isEqualTo(DocumentKind.LOAN);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(100_000L);
        assertThat(ledger.findByBusinessIdAndPartyIdAndAccountSideAndReason(
                shop.businessId(), shop.customerId(), AccountSide.RECEIVABLE, LedgerReason.LOAN
        )).hasSize(1);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("0");

        UUID supplier = parties.create(shop.userId(), "Bags Co", PartyRole.SUPPLIER).getId();
        when(model.complete("Agarwal se maal khareeda, 1000 dene hain")).thenReturn("""
                {"turn":"new_action","action":"supplier_payable","party_name":"Bags Co","amount_paise":100000,"cause":"goods","requested_tools":[]}
                """);
        InterpretationOutcome payable = interpreter.interpret(shop.userId(), "Agarwal se maal khareeda, 1000 dene hain", DAY, AT);
        UUID payableDocument = drafts.approve(shop.userId(), payable.draft().id(), payable.draft().version(), payable.draft().contentHash());

        assertThat(documents.findById(payableDocument).orElseThrow().getKind()).isEqualTo(DocumentKind.PAYABLE_NOTE);
        assertThat(posting.supplierPayablePaise(shop.userId(), supplier)).isEqualTo(100_000L);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(100_000L);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("0");
        assertThat(draftRows.findById(payable.draft().id()).orElseThrow().getProductId()).isNull();
    }

    @Test
    void aPronounUsesTheOnlyPartyWhoIsOwedAndAsksWhenSeveralAre() {
        Shop shop = shop();
        UUID rahul = parties.create(shop.userId(), "Rahul", PartyRole.CUSTOMER).getId();
        posting.recordOpeningReceivable(shop.userId(), UUID.randomUUID(), rahul, 150_000L, DAY, AT);
        InterpretationOutcome sale = awaitingSale(shop, "sale one");
        when(model.complete("usne 400 diye")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","amount_paise":40000,"payment_method":"Cash","requested_tools":[]}
                """);

        InterpretationOutcome payment = interpreter.interpret(shop.userId(), "usne 400 diye", sale.draft().id(), DAY, AT);

        Draft storedSale = draftRows.findById(sale.draft().id()).orElseThrow();
        Draft storedPayment = draftRows.findById(payment.draft().id()).orElseThrow();
        assertThat(storedSale.getAmountPaise()).isEqualTo(200_000L);
        assertThat(storedSale.getProductId()).isEqualTo(shop.productId());
        assertThat(storedPayment.getCustomerId()).isEqualTo(rahul);
        assertThat(storedPayment.getProductId()).isNull();
        assertThat(storedPayment.getLocationId()).isNull();

        UUID second = parties.create(shop.userId(), "Mehta", PartyRole.CUSTOMER).getId();
        posting.recordOpeningReceivable(shop.userId(), UUID.randomUUID(), second, 80_000L, DAY, AT);
        when(model.complete("usne 400 diye again")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","amount_paise":40000,"requested_tools":[]}
                """);
        InterpretationOutcome asked = interpreter.interpret(shop.userId(), "usne 400 diye again", DAY, AT);
        assertThat(asked.answer()).contains("Who was this?");
        assertThat(draftRows.findById(asked.draft().id()).orElseThrow().getCustomerId()).isNull();
        assertThat(draftRows.findById(asked.draft().id()).orElseThrow().getProductId()).isNull();
    }

    @Test
    void voiceAndTextSelectTheSamePaymentAndAVoiceQueryNeedsNoStockDraft() {
        Shop shop = shop();
        parties.create(shop.userId(), "Rahul", PartyRole.CUSTOMER);
        String payment = """
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"UPI","requested_tools":[]}
                """;
        when(model.complete("Rahul ke 1000 received")).thenReturn(payment);
        when(transcriber.transcribe(any(), anyString())).thenReturn("Rahul ke 1000 received");

        InterpretationOutcome text = interpreter.interpret(shop.userId(), "Rahul ke 1000 received", DAY, AT);
        VoiceReceipts.VoiceCapture voice = voices.capture(shop.userId(), "audio/ogg", "note.ogg", new byte[] {1, 2, 3}, DAY, AT);

        assertThat(draftRows.findById(text.draft().id()).orElseThrow().getKind()).isEqualTo("PAYMENT");
        assertThat(voice.draft()).isNotNull();
        assertThat(draftRows.findById(voice.draft().id()).orElseThrow().getKind()).isEqualTo("PAYMENT");
        assertThat(voice.evidenceId()).isNotNull();

        when(transcriber.transcribe(any(), anyString())).thenReturn("Agarwal ka balance");
        when(model.complete("Agarwal ka balance")).thenReturn("""
                {"action":"party_balance","party_name":"Agarwal","requested_tools":[]}
                """);
        VoiceReceipts.VoiceCapture query = voices.capture(shop.userId(), "audio/ogg", "balance.ogg", new byte[] {4}, DAY, AT);
        assertThat(query.draft()).isNull();
        assertThat(query.answer()).contains("Receivable");
        assertThat(query.evidenceId()).isNotNull();
    }

    @Test
    void aGodownAnswerFillsOnlyTheDraftThatIsMissingALocation() {
        Shop shop = shop();
        godowns.save(new Godown(UUID.randomUUID(), shop.businessId(), "g3", "Godown 3"));
        UUID supplier = parties.create(shop.userId(), "Bags Co", PartyRole.SUPPLIER).getId();
        when(model.complete("maal aaya")).thenReturn("""
                {"intent":"receive","party_name":"Bags Co","product_name":"Office folder","quantity":"1","unit":"box","requested_tools":[]}
                """);
        InterpretationOutcome receipt = interpreter.interpret(shop.userId(), "maal aaya", DAY, AT);
        InterpretationOutcome sale = awaitingSale(shop, "sale ready");
        when(model.complete("Godown 3")).thenReturn("""
                {"intent":null,"location_code":"g3","requested_tools":[]}
                """);

        InterpretationOutcome filled = interpreter.interpret(shop.userId(), "Godown 3", DAY, AT);
        assertThat(filled.draft().id()).isEqualTo(receipt.draft().id());

        Draft storedReceipt = draftRows.findById(receipt.draft().id()).orElseThrow();
        Draft storedSale = draftRows.findById(sale.draft().id()).orElseThrow();
        assertThat(storedReceipt.getKind()).isEqualTo("RECEIPT");
        assertThat(storedReceipt.getLocationId()).isNotEqualTo(shop.godownId());
        assertThat(godowns.findById(storedReceipt.getLocationId()).orElseThrow().getCode()).isEqualTo("g3");
        assertThat(storedReceipt.getCustomerId()).isEqualTo(supplier);
        assertThat(storedSale.getKind()).isEqualTo("SALE");
        assertThat(storedSale.getLocationId()).isEqualTo(shop.godownId());
        assertThat(storedSale.getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(storedSale.getAmountPaise()).isEqualTo(200_000L);
    }

    @Test
    void cancelResumeStaleApprovalAndABusinessSwitchDoNotDoublePostOrMoveOwnership() {
        Shop shop = shop();
        parties.create(shop.userId(), "Rahul", PartyRole.CUSTOMER);
        InterpretationOutcome sale = awaitingSale(shop, "sale to suspend");
        when(model.complete("pay")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"Cash","requested_tools":[]}
                """);
        InterpretationOutcome payment = interpreter.interpret(shop.userId(), "pay", sale.draft().id(), DAY, AT);
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.SUSPENDED);
        drafts.resume(shop.userId(), sale.draft().id());
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getAmountPaise()).isEqualTo(200_000L);

        drafts.cancel(shop.userId(), payment.draft().id());
        assertThat(draftRows.findById(payment.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELED);
        assertThat(documents.findByOperationId(payment.draft().id())).isEmpty();

        when(model.complete("pay again")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"Cash","requested_tools":[]}
                """);
        InterpretationOutcome current = interpreter.interpret(shop.userId(), "pay again", DAY, AT);
        long staleVersion = current.draft().version();
        String staleHash = current.draft().contentHash();
        when(model.complete("make it 400")).thenReturn("""
                {"turn":"edit_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":40000,"payment_method":"Cash","requested_tools":[]}
                """);
        InterpretationOutcome edited = interpreter.interpret(shop.userId(), "make it 400", current.draft().id(), DAY, AT);
        assertThatThrownBy(() -> drafts.approve(shop.userId(), current.draft().id(), staleVersion, staleHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This approval is no longer current.");
        UUID posted = drafts.approve(shop.userId(), edited.draft().id(), edited.draft().version(), edited.draft().contentHash());
        UUID replay = drafts.approve(shop.userId(), edited.draft().id(), edited.draft().version(), edited.draft().contentHash());
        assertThat(replay).isEqualTo(posted);
        assertThat(posting.customerReceivablePaise(shop.userId(), parties.findByName(shop.userId(), "Rahul").get(0).getId())).isEqualTo(-40_000L);

        Business other = businesses.save(new Business(UUID.randomUUID(), "Town Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), other.getId(), shop.userId(), MembershipRole.OWNER));
        tenants.selectBusiness(shop.userId(), other.getId());
        parties.create(shop.userId(), "Town Rahul", PartyRole.CUSTOMER);
        when(model.complete("town pay")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Town Rahul","amount_paise":100000,"payment_method":"Cash","requested_tools":[]}
                """);
        InterpretationOutcome town = interpreter.interpret(shop.userId(), "town pay", DAY, AT);
        assertThat(draftRows.findById(town.draft().id()).orElseThrow().getBusinessId()).isEqualTo(other.getId());
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getBusinessId()).isEqualTo(shop.businessId());
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getAmountPaise()).isEqualTo(200_000L);
    }

    private InterpretationOutcome awaitingSale(Shop shop, String text) {
        when(model.complete(text)).thenReturn("""
                {"intent":"sale","party_name":"Agarwal","product_name":"Office folder","quantity":"2","unit":"box","amount_paise":200000,"location_code":"g1","requested_tools":[]}
                """);
        return interpreter.interpret(shop.userId(), text, DAY, AT);
    }

    private void openBoxes(Shop shop, String boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "recovery");
        counts.propose(shop.userId(), sessionId, shop.productId(), new BigDecimal(boxes), "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId, "REC-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        UUID customerId = parties.create(userId, "Agarwal", PartyRole.CUSTOMER).getId();
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customerId);
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
    @Autowired private ConversationMemory reviewMemory;
    @Autowired private ConversationTurnRepository reviewTurns;
    @Test void reviewMemoryMustKeepLatestExchange() {
        Shop s=shop();
        for(int i=1;i<=12;i++) reviewMemory.remember(s.userId(), i%2==1?"owner":"assistant", "TURN-"+i+"-END");
        assertThat(reviewMemory.recent(s.userId())).contains("TURN-11-END","TURN-12-END");
    }
    @Test void reviewHindiCancelMustActuallyCancelOpenDraft() {
        Shop s=shop(); var old=awaitingSale(s,"review sale");
        when(model.complete("रद्द करो")).thenReturn("{\"turn\":\"cancel\",\"action\":null}");
        interpreter.interpret(s.userId(),"रद्द करो",DAY,AT);
        assertThat(draftRows.findById(old.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELED);
    }


    @Test void cancellationAsksWhenSeveralDraftsAreOpen() {
        Shop s=shop(); var pair=twoIndependentDrafts(s); var first=pair[0]; var second=pair[1];
        when(model.complete("रद्द करो")).thenReturn("{\"turn\":\"cancel\",\"action\":null}");
        var result=interpreter.interpret(s.userId(),"रद्द करो",DAY,AT);
        assertThat(result.answer()).contains("Which draft");
        assertThat(draftRows.findById(first.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(draftRows.findById(second.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
    }
    @Test void cancellationUsesOnlyAnOpenDraftInThisBusiness() {
        Shop s=shop(); var pair=twoIndependentDrafts(s); var first=pair[0]; var second=pair[1];
        when(model.complete("cancel the second one")).thenReturn("{\"turn\":\"cancel\",\"target_draft_id\":\""+second.draft().id()+"\"}");
        interpreter.interpret(s.userId(),"cancel the second one",DAY,AT);
        assertThat(draftRows.findById(second.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELED);
        when(model.complete("cancel another business")).thenReturn("{\"turn\":\"cancel\",\"target_draft_id\":\""+UUID.randomUUID()+"\"}");
        var blocked=interpreter.interpret(s.userId(),"cancel another business",DAY,AT);
        assertThat(blocked.answer()).contains("Nothing was canceled");
        assertThat(draftRows.findById(first.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
    }

    private InterpretationOutcome[] twoIndependentDrafts(Shop s) {
        when(model.complete("independent-sale")).thenReturn("""
          {"turn":"new_action","action":"sale","party_name":"Agarwal","product_name":"Office folder","quantity":"1","unit":"box","amount_paise":100000,"location_code":"g1"}
          """);
        return new InterpretationOutcome[] {interpreter.interpret(s.userId(),"independent-sale",DAY,AT), interpreter.interpret(s.userId(),"independent-sale",DAY,AT)};
    }

    @Test void anishaPartyAnswerContinuesPayableQuery() {
        Shop s=shop(); UUID supplier=parties.create(s.userId(),"Anisha Gajak",PartyRole.SUPPLIER).getId();
        posting.recordSupplierObligation(s.userId(),UUID.randomUUID(),supplier,500000L,"new",DAY,AT);
        when(model.complete("Anisha ke panch hajar dene ha")).thenReturn("""
          {"action":"party_balance","party_name":"Anisha","amount_paise":500000,"balance_side":"payable"}
          """);
        interpreter.interpret(s.userId(),"Anisha ke panch hajar dene ha",DAY,AT);
        when(model.complete("Party Anisha Gajak")).thenReturn("""
          {"turn":"answer_to_clarification","action":null,"party_name":"Anisha Gajak"}
          """);
        var result=interpreter.interpret(s.userId(),"Party Anisha Gajak",DAY,AT);
        assertThat(result.draft()).isNull();
        assertThat(result.answer()).contains("Payable","₹5000.00","Anisha Gajak");
        assertThat(posting.supplierPayablePaise(s.userId(),supplier)).isEqualTo(500000L);
    }
    @Test void unknownAnishaRemainsAQuestionInsteadOfAGreeting() {
        Shop s=shop();
        when(model.complete("Anisha ko 5000 dene hain")).thenReturn("""
          {"action":"party_balance","party_name":"Anisha","amount_paise":500000,"balance_side":"payable"}
          """);
        interpreter.interpret(s.userId(),"Anisha ko 5000 dene hain",DAY,AT);
        when(model.complete("Party Anisha Gajak")).thenReturn("""
          {"turn":"answer_to_clarification","action":null,"party_name":"Anisha Gajak"}
          """);
        var result=interpreter.interpret(s.userId(),"Party Anisha Gajak",DAY,AT);
        assertThat(result.draft()).isNull();
        assertThat(result.answer()).contains("Anisha Gajak").doesNotContain("Namaste");
    }
    @Test void payableStatementAsksWhetherDebtIsNewBeforeApproval() {
        Shop s=shop();parties.create(s.userId(),"Anisha Gajak",PartyRole.SUPPLIER);
        when(model.complete("Anisha Gajak ko 5000 dene hain")).thenReturn("""
          {"turn":"new_action","action":"supplier_payable","party_name":"Anisha Gajak","amount_paise":500000}
          """);
        var result=interpreter.interpret(s.userId(),"Anisha Gajak ko 5000 dene hain",DAY,AT);
        assertThat(result.draft().status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
        assertThat(result.draft().missingFields()).contains("cause");
    }

    @Test void unknownSupplierCanBeCreatedThenNewPayableExplicitlyConfirmed() {
        Shop s=shop();
        when(model.complete("Anisha ko 5000 dene hain")).thenReturn("""
          {"action":"party_balance","party_name":"Anisha","amount_paise":500000,"balance_side":"payable"}
          """);
        interpreter.interpret(s.userId(),"Anisha ko 5000 dene hain",DAY,AT);
        when(model.complete("Party Anisha Gajak")).thenReturn("""
          {"turn":"answer_to_clarification","party_name":"Anisha Gajak"}
          """);
        interpreter.interpret(s.userId(),"Party Anisha Gajak",DAY,AT);
        when(model.complete("create supplier Anisha Gajak")).thenReturn("""
          {"turn":"answer_to_clarification","party_name":"Anisha Gajak","create_party":true}
          """);
        var created=interpreter.interpret(s.userId(),"create supplier Anisha Gajak",DAY,AT);
        assertThat(created.answer()).contains("Payable is ₹0.00","new amount");
        UUID supplier=parties.findByName(s.userId(),"Anisha Gajak").get(0).getId();
        assertThat(posting.supplierPayablePaise(s.userId(),supplier)).isZero();
        when(model.complete("ye naya amount hai")).thenReturn("""
          {"turn":"answer_to_clarification","cause":"new"}
          """);
        var proposed=interpreter.interpret(s.userId(),"ye naya amount hai",DAY,AT);
        assertThat(proposed.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(posting.supplierPayablePaise(s.userId(),supplier)).isZero();
        drafts.approve(s.userId(),proposed.draft().id(),proposed.draft().version(),proposed.draft().contentHash());
        assertThat(posting.supplierPayablePaise(s.userId(),supplier)).isEqualTo(500000L);
    }
    @Test void pendingPartyQuestionDoesNotCarryIntoAnotherBusinessOrNewAction() {
        Shop s=shop();
        when(model.complete("who do I owe")).thenReturn("{\"action\":\"party_balance\",\"balance_side\":\"payable\"}");
        interpreter.interpret(s.userId(),"who do I owe",DAY,AT);
        Business other=businesses.save(new Business(UUID.randomUUID(),"Other balance shop","Asia/Kolkata","INR"));
        memberships.save(new Membership(UUID.randomUUID(),other.getId(),s.userId(),MembershipRole.OWNER));
        tenants.selectBusiness(s.userId(),other.getId());
        when(model.complete("Party Anisha Gajak")).thenReturn("{\"turn\":\"answer_to_clarification\",\"party_name\":\"Anisha Gajak\"}");
        var separate=interpreter.interpret(s.userId(),"Party Anisha Gajak",DAY,AT);
        assertThat(separate.answer()).doesNotContain("balance question is still open");
        tenants.selectBusiness(s.userId(),s.businessId());
        when(model.complete("new sale")).thenReturn("{\"turn\":\"new_action\",\"action\":\"sale\"}");
        interpreter.interpret(s.userId(),"new sale",DAY,AT);
        var noOldQuery=interpreter.interpret(s.userId(),"Party Anisha Gajak",DAY,AT);
        assertThat(noOldQuery.answer()).doesNotContain("balance question is still open");
    }

    @Test void cancelingABalanceQuestionLeavesAnUnrelatedSaleIntact() {
        Shop s=shop(); var sale=awaitingSale(s,"sale before question");
        when(model.complete("whose balance")).thenReturn("{\"action\":\"party_balance\",\"balance_side\":\"payable\"}");
        interpreter.interpret(s.userId(),"whose balance",DAY,AT);
        when(model.complete("रद्द करो")).thenReturn("{\"turn\":\"cancel\"}");
        var canceled=interpreter.interpret(s.userId(),"रद्द करो",DAY,AT);
        assertThat(canceled.answer()).contains("balance question");
        assertThat(draftRows.findById(sale.draft().id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
    }

}
