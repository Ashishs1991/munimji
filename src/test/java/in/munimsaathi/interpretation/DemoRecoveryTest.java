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

    @Autowired private DialogueState dialogueState;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void aProductLocationQueryCanFindStockWithoutALabelNote() {
        Shop sh = shop();
        openBoxes(sh, "5");
        when(model.complete("Office folder kahan hai")).thenReturn("""
                {"turn":"read_query","action":"locate_goods","product_name":"Office folder","location_label":"Office folder"}
                """);
        var answer = interpreter.interpret(sh.userId(), "Office folder kahan hai", DAY, AT);
        assertThat(answer.draft()).isNull();
        assertThat(answer.answer()).contains("Godown 1", "stock hai");
        assertThat(counts.balance(sh.userId(), sh.productId(), sh.godownId())).isEqualByComparingTo("50");
    }

    @Test
    void repeatingASuggestedSavedPartyNameResolvesItWithoutCreatingAnother() {
        Shop sh = shop();
        UUID party = sh.customerId();
        when(model.complete("Aggarwal paid")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Aggarwal","amount_paise":100000,"payment_method":"Cash"}
                """);
        var first = interpreter.interpret(sh.userId(), "Aggarwal paid", DAY, AT);
        assertThat(first.answer()).contains("Agarwal");
        var corrected = interpreter.interpret(sh.userId(), "Party Agarwal", DAY, AT);
        assertThat(corrected.draft().id()).isEqualTo(first.draft().id());
        assertThat(corrected.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(drafts.inspect(sh.userId(), first.draft().id()).getCustomerId()).isEqualTo(party);
        assertThat(parties.findByName(sh.userId(), "Aggarwal")).isEmpty();
    }

    @Test
    void staleCreationConsentDoesNotOverwriteANewerPartySelection() {
        Shop sh = shop();
        UUID saved = parties.create(sh.userId(), "Saved borrower", PartyRole.CUSTOMER).getId();
        when(model.complete("pending stale loan")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Unknown borrower","amount_paise":1000000,"payment_purpose":"loan","payment_method":"Cash"}
                """);
        var first = interpreter.interpret(sh.userId(), "pending stale loan", DAY, AT);
        drafts.continueMoney(sh.userId(), first.draft().id(), "outgoing_payment", saved, null, null, null, null, null, DAY, AT);
        var yes = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(yes.draft().id()).isEqualTo(first.draft().id());
        assertThat(parties.findByName(sh.userId(), "Unknown borrower")).isEmpty();
        assertThat(drafts.inspect(sh.userId(), first.draft().id()).getCustomerId()).isEqualTo(saved);
    }

    @Test
    void pendingQuestionsAreScopedToBusinessActorAndExpiry() {
        Shop sh = shop();
        when(model.complete("private loan question")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Private borrower","amount_paise":1000000,"payment_purpose":"loan","payment_method":"Cash"}
                """);
        interpreter.interpret(sh.userId(), "private loan question", DAY, AT);
        assertThat(interpreter.clarificationPending(sh.userId())).isTrue();
        UUID colleague = UUID.randomUUID();
        memberships.save(new Membership(UUID.randomUUID(), sh.businessId(), colleague, MembershipRole.OWNER));
        tenants.selectBusiness(colleague, sh.businessId());
        assertThat(dialogueState.get(colleague)).isEmpty();
        Business other = businesses.save(new Business(UUID.randomUUID(), "Other question business", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), other.getId(), sh.userId(), MembershipRole.OWNER));
        tenants.selectBusiness(sh.userId(), other.getId());
        assertThat(dialogueState.get(sh.userId())).isEmpty();
        tenants.selectBusiness(sh.userId(), sh.businessId());
        jdbc.update("update dialogue_state set expires_at = ? where business_id = ? and actor_id = ?",
                java.sql.Timestamp.from(Instant.EPOCH), sh.businessId(), sh.userId());
        assertThat(dialogueState.get(sh.userId())).isEmpty();
    }

    @Test
    void interpretationTraceRecordsRawRoutingAndTheResolvedAction() {
        Shop sh = shop();
        String raw = "{\"turn\":\"new_action\",\"action\":\"goods_received\",\"party_name\":\"Anil\",\"location_code\":\"g1\"}";
        when(model.complete("Anil ka maal Godown 1 mein rakha hai")).thenReturn(raw);
        var note = interpreter.interpret(sh.userId(), "Anil ka maal Godown 1 mein rakha hai", DAY, AT);
        var trace = jdbc.queryForMap("select model_reply,resolved_kind,draft_id from interpretation_turns where business_id=? order by created_at desc limit 1", sh.businessId());
        assertThat(trace.get("model_reply")).isEqualTo(raw);
        assertThat(trace.get("resolved_kind")).isEqualTo("LOCATION");
        assertThat(trace.get("draft_id")).isEqualTo(note.draft().id());
    }

    @Test
    void actionClarificationRetainsQuantityAndCreatesNoStockUntilApproval() {
        Shop sh = shop();
        parties.create(sh.userId(), "Supplier", PartyRole.SUPPLIER);
        when(model.complete("5 bori plastic")).thenReturn("""
                {"turn":"new_action","party_name":"Supplier","product_name":"Plastic bags","quantity":"5","unit":"bori","location_code":"g1"}
                """);
        var vague = interpreter.interpret(sh.userId(), "5 bori plastic", DAY, AT);
        assertThat(vague.answer()).contains("naya maal aaya", "sirf");
        var receipt = interpreter.interpret(sh.userId(), "maal aaya", DAY, AT);
        assertThat(receipt.draft().id()).isEqualTo(vague.draft().id());
        assertThat(drafts.inspect(sh.userId(), receipt.draft().id()).getKind()).isEqualTo("RECEIPT");
        var product = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(product.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        var row = drafts.inspect(sh.userId(), product.draft().id());
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("5");
        assertThat(counts.balance(sh.userId(), row.getProductId(), sh.godownId())).isEqualByComparingTo("0");
    }

    @Test
    void quantityAddedToALocationNoteNeedsAnActionInsteadOfBecomingAPurchase() {
        Shop sh = shop();
        when(model.complete("Anil ka maal Godown 1 mein rakha hai")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Anil","location_code":"g1"}
                """);
        var note = interpreter.interpret(sh.userId(), "Anil ka maal Godown 1 mein rakha hai", DAY, AT);
        when(model.complete("Plastic boxes ki 5 bori")).thenReturn("""
                {"turn":"answer_to_clarification","target_draft_id":"%s","product_name":"Plastic boxes","quantity":"5","unit":"bori"}
                """.formatted(note.draft().id()));
        var detail = interpreter.interpret(sh.userId(), "Plastic boxes ki 5 bori", DAY, AT);
        assertThat(drafts.inspect(sh.userId(), note.draft().id()).getStatus()).isEqualTo(DraftStatus.SUSPENDED);
        assertThat(drafts.inspect(sh.userId(), detail.draft().id()).getKind()).isNull();
        assertThat(detail.answer()).contains("naya maal aaya", "sirf");
        assertThat(drafts.inspect(sh.userId(), detail.draft().id()).getDisplayQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void knownCustomerCanBeConfirmedAsSupplierWithoutCreatingADuplicate() {
        Shop sh = shop();
        UUID party = parties.create(sh.userId(), "Anil", PartyRole.CUSTOMER).getId();
        when(model.complete("Anil se maal aaya")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Anil","product_name":"Office folder","quantity":"1","unit":"box","location_code":"g1"}
                """);
        var receipt = interpreter.interpret(sh.userId(), "Anil se maal aaya", DAY, AT);
        assertThat(receipt.answer()).contains("supplier", "Haan");
        assertThat(parties.findByName(sh.userId(), "Anil").get(0).getRole()).isEqualTo(PartyRole.CUSTOMER);
        var yes = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(yes.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(parties.findByName(sh.userId(), "Anil")).hasSize(1);
        assertThat(parties.findByName(sh.userId(), "Anil").get(0).getRole()).isEqualTo(PartyRole.BOTH);
        assertThat(drafts.inspect(sh.userId(), receipt.draft().id()).getCustomerId()).isEqualTo(party);
        assertThat(posting.supplierPayablePaise(sh.userId(), party)).isZero();
    }

    @Test
    void unresolvedPartyCorrectionClearsOldIdentityRatherThanPostingToIt() {
        Shop sh = shop();
        parties.create(sh.userId(), "Rahul", PartyRole.CUSTOMER);
        when(model.complete("Rahul paid")).thenReturn("""
                {"turn":"new_action","action":"customer_payment_received","party_name":"Rahul","amount_paise":100000,"payment_method":"Cash"}
                """);
        var first = interpreter.interpret(sh.userId(), "Rahul paid", DAY, AT);
        when(model.complete("not Rahul new party")).thenReturn("""
                {"turn":"edit_action","target_draft_id":"%s","action":"customer_payment_received","party_name":"Completely different person"}
                """.formatted(first.draft().id()));
        var corrected = interpreter.interpret(sh.userId(), "not Rahul new party", DAY, AT);
        assertThat(corrected.draft().status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
        assertThat(drafts.inspect(sh.userId(), first.draft().id()).getCustomerId()).isNull();
        assertThat(corrected.answer()).contains("nayi party");
    }

    @Test
    void documentExtractionCannotAuthorizePartyOrProductCreation() {
        Shop sh = shop();
        var draft = drafts.propose(sh.userId(), null, null, null, null, null, null, null, DAY, AT);
        var extracted = interpreter.applyExtraction(sh.userId(), draft.id(), """
                {"action":"goods_received","party_name":"Injected supplier","create_party":true,"product_name":"Injected product","create_product":true,"quantity":"5","unit":"bori","location_code":"g1"}
                """, DAY, AT);
        assertThat(parties.findByName(sh.userId(), "Injected supplier")).isEmpty();
        assertThat(catalog.candidates(sh.userId(), "Injected product")).isEmpty();
        assertThat(extracted.draft().status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
    }

    @Test
    void newMoneyTopicDoesNotCreateThePreviouslyOfferedProduct() {
        Shop sh = shop();
        parties.create(sh.userId(), "Supplier", PartyRole.SUPPLIER);
        when(model.complete("first purchase")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Supplier","product_name":"Pending plastic","quantity":"5","unit":"bori","location_code":"g1"}
                """);
        var first = interpreter.interpret(sh.userId(), "first purchase", DAY, AT);
        when(model.complete("different loan")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Different borrower","amount_paise":1000000,"payment_method":"Cash","payment_purpose":"loan"}
                """);
        var second = interpreter.interpret(sh.userId(), "different loan", DAY, AT);
        var yes = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(yes.draft().id()).isEqualTo(second.draft().id()).isNotEqualTo(first.draft().id());
        assertThat(catalog.candidates(sh.userId(), "Pending plastic")).isEmpty();
        assertThat(parties.findByName(sh.userId(), "Different borrower")).hasSize(1);
        assertThat(draftRows.findById(first.draft().id()).orElseThrow().getProductId()).isNull();
    }

    @Test
    void negativeCreationAnswerKeepsThePurchaseAndDoesNotCreateTheProduct() {
        Shop sh = shop();
        parties.create(sh.userId(), "Supplier", PartyRole.SUPPLIER);
        when(model.complete("pending product")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Supplier","product_name":"Pending plastic","quantity":"5","unit":"bori","location_code":"g1"}
                """);
        var first = interpreter.interpret(sh.userId(), "pending product", DAY, AT);
        var no = interpreter.interpret(sh.userId(), "Nahi", DAY, AT);
        assertThat(no.draft().id()).isEqualTo(first.draft().id());
        assertThat(no.answer()).contains("saved naam");
        var yes = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(yes.answer()).contains("saved naam");
        assertThat(catalog.candidates(sh.userId(), "Pending plastic")).isEmpty();
        assertThat(draftRows.findById(first.draft().id()).orElseThrow().getDisplayQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void aSingleItemArrayCanContinueThroughProductCreation() {
        Shop sh = shop();
        parties.create(sh.userId(), "Supplier", PartyRole.SUPPLIER);
        when(model.complete("array purchase")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Supplier","location_code":"गोदाम वन","lines":[{"product_name":"Plastic boxes","quantity":"5","unit":"bori"}]}
                """);
        var first = interpreter.interpret(sh.userId(), "array purchase", DAY, AT);
        assertThat(first.answer()).contains("naya product", "bori");
        var yes = interpreter.interpret(sh.userId(), "Haan", DAY, AT);
        assertThat(yes.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        var row = draftRows.findById(first.draft().id()).orElseThrow();
        assertThat(row.getLocationId()).isEqualTo(sh.godownId());
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void storageStatementCreatesAnApprovedLocationNoteNotPurchaseStock() {
        Shop sh = shop();
        String instruction = "Anil bhai ka maal Godown 1 mein rakha hai";
        when(model.complete(instruction)).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Anil bhai","location_code":"g1"}
                """);
        var result = interpreter.interpret(sh.userId(), instruction, DAY, AT);
        Draft note = draftRows.findById(result.draft().id()).orElseThrow();
        assertThat(note.getKind()).isEqualTo("LOCATION");
        assertThat(note.getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(note.getProductId()).isNull();
        assertThat(note.getDisplayQuantity()).isNull();
        UUID locationId = drafts.approve(sh.userId(), note.getId(), result.draft().version(), result.draft().contentHash());
        assertThat(drafts.approve(sh.userId(), note.getId(), result.draft().version(), result.draft().contentHash())).isEqualTo(locationId);
        assertThat(counts.balance(sh.userId(), sh.productId(), sh.godownId())).isEqualByComparingTo("0");
        assertThat(documents.findByOperationId(note.getId())).isEmpty();
        when(model.complete("Anil bhai ka maal kahan hai")).thenReturn("""
                {"turn":"read_query","action":"locate_goods","location_label":"Anil bhai ka maal"}
                """);
        var answer = interpreter.interpret(sh.userId(), "Anil bhai ka maal kahan hai", DAY, AT);
        assertThat(answer.draft()).isNull();
        assertThat(answer.answer()).contains("Godown 1");
    }

    @Test
    void naturalYesCreatesTheOfferedPartyAndKeepsTheLoanFacts() {
        Shop sh = shop();
        when(model.complete("Anil bhai ko 10000 udhaar cash diye")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Anil bhai","amount_paise":1000000,"payment_purpose":"loan","payment_method":"Cash"}
                """);
        var loan = interpreter.interpret(sh.userId(), "Anil bhai ko 10000 udhaar cash diye", DAY, AT);
        assertThat(loan.answer()).contains("Anil bhai", "nayi party");
        var answer = interpreter.interpret(sh.userId(), "हाँ", DAY, AT);
        assertThat(answer.draft().id()).isEqualTo(loan.draft().id());
        assertThat(answer.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(draftRows.findById(loan.draft().id()).orElseThrow().getAmountPaise()).isEqualTo(1000000L);
        assertThat(parties.findByName(sh.userId(), "Anil bhai")).hasSize(1);
        assertThat(posting.customerReceivablePaise(sh.userId(), parties.findByName(sh.userId(), "Anil bhai").get(0).getId())).isZero();
    }

    @Test
    void repeatedProductNameOffersCreationAndYesUsesTheKnownUnit() {
        Shop sh = shop();
        parties.create(sh.userId(), "Anil bhai", PartyRole.SUPPLIER);
        when(model.complete("Anil se 5 bori Plastic boxes aaya Godown 1")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"Anil bhai","product_name":"Plastic boxes","quantity":"5","unit":"bori","location_code":"g1"}
                """);
        var receipt = interpreter.interpret(sh.userId(), "Anil se 5 bori Plastic boxes aaya Godown 1", DAY, AT);
        assertThat(receipt.answer()).contains("Plastic boxes", "bori", "naya product");
        var repeat = interpreter.interpret(sh.userId(), "Product - plastic boxes", DAY, AT);
        assertThat(repeat.draft().id()).isEqualTo(receipt.draft().id());
        assertThat(repeat.answer()).contains("Plastic boxes", "Haan").doesNotContain("Kaunsa product");
        assertThat(catalog.candidates(sh.userId(), "Plastic boxes")).isEmpty();
        var confirmed = interpreter.interpret(sh.userId(), "haan bana do", DAY, AT);
        assertThat(confirmed.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        Draft row = draftRows.findById(receipt.draft().id()).orElseThrow();
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("5");
        assertThat(row.getUnitLabel()).isEqualTo("bori");
        assertThat(row.getLocationId()).isEqualTo(sh.godownId());
        assertThat(counts.balance(sh.userId(), row.getProductId(), sh.godownId())).isEqualByComparingTo("0");
    }

    @Test
    void conflictingHaanNahiTranscriptDoesNotCreateAParty() {
        Shop sh = shop();
        when(model.complete("new Anil loan")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Anil bhai","amount_paise":1000000,"payment_purpose":"loan","payment_method":"Cash"}
                """);
        var draft = interpreter.interpret(sh.userId(), "new Anil loan", DAY, AT);
        var answer = interpreter.interpret(sh.userId(), "हां नहीं पार्टी है अनिल भाई", DAY, AT);
        assertThat(answer.draft().id()).isEqualTo(draft.draft().id());
        assertThat(answer.answer()).contains("nayi party", "purani party");
        assertThat(parties.findByName(sh.userId(), "Anil bhai")).isEmpty();
    }

    @Test
    void productCreationDoesNotRunInsideAMoneyCommand() {
        Shop sh = shop();
        when(model.complete("money with product flag")).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Agarwal","amount_paise":100000,"payment_method":"Cash","payment_purpose":"loan","product_name":"Unexpected stock","unit":"bori","create_product":true}
                """);
        var result = interpreter.interpret(sh.userId(), "money with product flag", DAY, AT);
        assertThat(result.draft()).isNull();
        assertThat(catalog.candidates(sh.userId(), "Unexpected stock")).isEmpty();
        assertThat(drafts.open(sh.userId())).isEmpty();
    }

    @Test
    void ambiguousPurchaseFollowUpDoesNotCreateAThirdDraftOrParty() {
        Shop sh = shop();
        var one = drafts.propose(sh.userId(), "receive", null, null, sh.godownId(), BigDecimal.ONE, "bori", null, DAY, AT);
        var two = drafts.propose(sh.userId(), "receive", null, null, sh.godownId(), BigDecimal.TEN, "bori", null, DAY, AT);
        when(model.complete("supplier create")).thenReturn("""
                {"turn":"answer_to_clarification","party_name":"Agarwal Petha","create_party":true}
                """);
        var answer = interpreter.interpret(sh.userId(), "supplier create", DAY, AT);
        assertThat(answer.draft()).isNull();
        assertThat(answer.answer()).contains(one.id().toString(), two.id().toString());
        assertThat(drafts.open(sh.userId())).hasSize(2);
        assertThat(parties.findByName(sh.userId(), "Agarwal Petha")).isEmpty();
    }

    @Test
    void foreignDraftTargetCannotCreateCatalogRecords() {
        Shop sh = shop();
        Shop other = shop();
        var foreign = drafts.propose(other.userId(), "receive", null, null, other.godownId(), BigDecimal.ONE, "bori", null, DAY, AT);
        when(model.complete("foreign target")).thenReturn("""
                {"turn":"answer_to_clarification","target_draft_id":"%s","party_name":"Unsafe supplier","create_party":true,"product_name":"Unsafe product","unit":"bori","create_product":true}
                """.formatted(foreign.id()));
        assertThatThrownBy(() -> interpreter.interpret(sh.userId(), "foreign target", DAY, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(parties.findByName(sh.userId(), "Unsafe supplier")).isEmpty();
        assertThat(catalog.candidates(sh.userId(), "Unsafe product")).isEmpty();
    }

    @Test
    void purchaseKeepsQuantityAndGodownAcrossExplicitPartyAndProductCreation() {
        Shop sh = shop();
        String instruction = "Agarwal Petha se 1000 bori plastic liya Godown 1 mein hai";
        when(model.complete(instruction)).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"अगरवाल पेठा","product_name":"प्लास्टिक","quantity":"1000","unit":"बोरी","location_code":"गोदाम १"}
                """);
        var receipt = interpreter.interpret(sh.userId(), instruction, DAY, AT);
        Draft row = draftRows.findById(receipt.draft().id()).orElseThrow();
        assertThat(row.getLocationId()).isEqualTo(sh.godownId());
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("1000");
        assertThat(row.getMissingFields()).doesNotContain("location");
        when(model.complete("supplier banao")).thenReturn("""
                {"turn":"answer_to_clarification","target_draft_id":"%s","party_name":"अगरवाल पेठा","create_party":true}
                """.formatted(receipt.draft().id()));
        var partyAnswer = interpreter.interpret(sh.userId(), "supplier banao", DAY, AT);
        assertThat(partyAnswer.draft().id()).isEqualTo(receipt.draft().id());
        assertThat(parties.findByName(sh.userId(), "अगरवाल पेठा").get(0).getRole()).isEqualTo(PartyRole.SUPPLIER);
        when(model.complete("plastic product banao bori mein")).thenReturn("""
                {"turn":"answer_to_clarification","target_draft_id":"%s","product_name":"प्लास्टिक","unit":"बोरी","create_product":true}
                """.formatted(receipt.draft().id()));
        var productAnswer = interpreter.interpret(sh.userId(), "plastic product banao bori mein", DAY, AT);
        assertThat(productAnswer.draft().id()).isEqualTo(receipt.draft().id());
        assertThat(productAnswer.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        row = draftRows.findById(receipt.draft().id()).orElseThrow();
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("1000");
        assertThat(row.getLocationId()).isEqualTo(sh.godownId());
        assertThat(counts.balance(sh.userId(), row.getProductId(), sh.godownId())).isEqualByComparingTo("0");
        drafts.approve(sh.userId(), row.getId(), productAnswer.draft().version(), productAnswer.draft().contentHash());
        assertThat(counts.balance(sh.userId(), row.getProductId(), sh.godownId())).isEqualByComparingTo("1000");
        assertThat(posting.supplierPayablePaise(sh.userId(), row.getCustomerId())).isZero();
    }

    @Test
    void modelContextIncludesCatalogLocationsAndUnresolvedPurchaseDetails() {
        Shop sh = shop();
        when(model.complete("unresolved purchase")).thenReturn("""
                {"turn":"new_action","action":"goods_received","party_name":"अगरवाल पेठा","product_name":"प्लास्टिक","quantity":"1000","unit":"बोरी","location_code":"Godown 1"}
                """);
        var result = interpreter.interpret(sh.userId(), "unresolved purchase", DAY, AT);
        when(model.complete("context probe")).thenAnswer(call -> {
            assertThat(TurnContext.current()).contains("Office folder", "Godown 1", "g1", "अगरवाल पेठा", "प्लास्टिक", "1000", "बोरी", result.draft().id().toString());
            return "{\"turn\":\"unclear\",\"reply\":\"Party ka naam confirm karein\"}";
        });
        interpreter.interpret(sh.userId(), "context probe", DAY, AT);
    }

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
        assertThat(asked.answer()).contains("nayi party", "Haan");
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

    @Test void moneyAlreadyGivenIsNotANewPayable() {
        Shop s=shop(); parties.create(s.userId(),"Anisha Gajak",PartyRole.SUPPLIER);
        when(model.complete("maine Anisha ko panch hajar diye")).thenReturn("""
          {"turn":"new_action","action":"outgoing_payment","party_name":"Anisha Gajak","amount_paise":500000}
          """);
        var result=interpreter.interpret(s.userId(),"maine Anisha ko panch hajar diye",DAY,AT);
        assertThat(draftRows.findById(result.draft().id()).orElseThrow().getKind()).isEqualTo("OUTGOING_PAYMENT");
        assertThat(result.draft().missingFields()).contains("payment_purpose").doesNotContain("product","quantity","location");
    }
    @Test void paidCorrectionKeepsAmountThenAdvancePostsOnce() {
        Shop s=shop(); UUID anisha=parties.create(s.userId(),"Anisha Gajak",PartyRole.SUPPLIER).getId();
        var mistaken=drafts.proposeMoney(s.userId(),"payable",anisha,500000L,null,null,"new","payable",DAY,AT);
        when(model.complete("maine pay kiye hai Anisha ko")).thenReturn("""
          {"turn":"edit_action","action":"outgoing_payment","party_name":"Anisha Gajak"}
          """);
        var corrected=interpreter.interpret(s.userId(),"maine pay kiye hai Anisha ko",DAY,AT);
        assertThat(corrected.draft().amountPaise()).isEqualTo(500000L);
        assertThat(draftRows.findById(mistaken.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.SUSPENDED);
        when(model.complete("advance tha")).thenReturn("{\"turn\":\"answer_to_clarification\",\"payment_purpose\":\"advance\"}");
        var purpose=interpreter.interpret(s.userId(),"advance tha",DAY,AT);
        assertThat(purpose.draft().id()).isEqualTo(corrected.draft().id());
        when(model.complete("cash")).thenReturn("{\"turn\":\"answer_to_clarification\",\"payment_method\":\"Cash\"}");
        var ready=interpreter.interpret(s.userId(),"cash",DAY,AT);
        UUID document=drafts.approve(s.userId(),ready.draft().id(),ready.draft().version(),ready.draft().contentHash());
        assertThat(drafts.approve(s.userId(),ready.draft().id(),ready.draft().version(),ready.draft().contentHash())).isEqualTo(document);
        assertThat(posting.supplierPayablePaise(s.userId(),anisha)).isEqualTo(-500000L);
        assertThat(counts.balance(s.userId(),s.productId(),s.godownId())).isEqualByComparingTo("0");
    }
    @Test void paidSupplierBillReducesPayableWhileLoanIncreasesReceivable() {
        for(String purpose: new String[]{"bill_payment","loan"}) {
            Shop s=shop(); UUID anisha=parties.create(s.userId(),"Anisha Gajak",PartyRole.SUPPLIER).getId();
            if(purpose.equals("bill_payment")) posting.recordSupplierObligation(s.userId(),UUID.randomUUID(),anisha,500000L,"new",DAY,AT);
            when(model.complete(purpose)).thenReturn("{\"turn\":\"new_action\",\"action\":\"outgoing_payment\",\"party_name\":\"Anisha Gajak\",\"amount_paise\":500000,\"payment_method\":\"Cash\",\"payment_purpose\":\""+purpose+"\"}");
            var proposed=interpreter.interpret(s.userId(),purpose,DAY,AT);
            drafts.approve(s.userId(),proposed.draft().id(),proposed.draft().version(),proposed.draft().contentHash());
            assertThat(posting.supplierPayablePaise(s.userId(),anisha)).isZero();
            if(purpose.equals("loan")) assertThat(posting.customerReceivablePaise(s.userId(),anisha)).isEqualTo(500000L);
            assertThat(counts.balance(s.userId(),s.productId(),s.godownId())).isEqualByComparingTo("0");
        }
    }

    @Test void aBareAmountWithoutAnActionAsksMoneyDirectionInsteadOfGoods() {
        Shop s=shop();
        when(model.complete("5000")).thenReturn("{\"turn\":\"new_action\",\"amount_paise\":500000}");
        var result=interpreter.interpret(s.userId(),"5000",DAY,AT);
        assertThat(result.draft()).isNull();
        assertThat(result.answer()).contains("₹5000.00","mile hain, diye hain");
    }

}
