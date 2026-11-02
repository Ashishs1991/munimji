package in.munimsaathi.eway;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.PostgresSupport;
import in.munimsaathi.config.OwnerTokens;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.interpretation.CommandInterpreter;
import in.munimsaathi.interpretation.InterpretationOutcome;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EwayBookTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final Instant AT = Instant.parse("2026-08-01T04:30:00Z");
    private static final EwayReply ACCEPTED = new EwayReply(
            "1",
            "123456789",
            "01/08/2026 10:00:00 AM",
            "02/08/2026 11:59:00 PM",
            null,
            true
    );

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
        registry.add("munim.supabase.jwt-secret", () -> "test-owner-secret");
        registry.add("munim.eway.enabled", () -> "true");
        registry.add("munim.eway.environment", () -> "sandbox");
        registry.add("munim.eway.base-url", () -> "https://eway.example");
        registry.add("munim.eway.client-id", () -> "client");
        registry.add("munim.eway.client-secret", () -> "secret");
        registry.add("munim.eway.gstin", () -> "29ABCDE8755F1Z2");
        registry.add("munim.eway.username", () -> "eway-user");
        registry.add("munim.eway.password", () -> "portal-secret");
    }

    @MockBean
    private EwayGateway gateway;

    @MockBean
    private LanguageModel model;

    @Autowired
    private EwayBook book;

    @Autowired
    private EwayDraftRepository drafts;

    @Autowired
    private CommandInterpreter interpreter;

    @Autowired
    private PostingService posting;

    @Autowired
    private PostedDocumentRepository documents;

    @Autowired
    private CountService counts;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private GodownRepository godowns;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void anAcceptedSandboxResponseStoresTheOfficialNumberAndValidity() throws Exception {
        Shop shop = shop();
        UUID saleId = sell(shop);
        UUID draftId = book.prepare(shop.userId(), saleId, details("UP80AB1234"));
        EwayDraftView prepared = book.view(shop.userId(), draftId);
        assertThat(prepared.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(prepared.environment()).isEqualTo("sandbox");
        assertThat(prepared.ewayBillNo()).isNull();
        assertThat(prepared.label()).doesNotContain("generated");
        assertThat(book.label(shop.userId(), draftId, true)).isEqualTo("local copy");
        assertThat(drafts.findById(draftId).orElseThrow().getPayload())
                .contains("\"docNo\":\"1\"")
                .contains("\"docType\":\"INV\"")
                .contains("1000.00")
                .doesNotContain("ewayBillNo");

        book.approve(shop.userId(), draftId);
        when(gateway.generate(anyString())).thenReturn(ACCEPTED);

        EwayDraftView confirmed = book.submit(shop.userId(), draftId);
        EwayDraftView again = book.submit(shop.userId(), draftId);

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.ewayBillNo()).isEqualTo("123456789");
        assertThat(confirmed.ewayBillDate()).isEqualTo("01/08/2026 10:00:00 AM");
        assertThat(confirmed.validUpto()).isEqualTo("02/08/2026 11:59:00 PM");
        assertThat(confirmed.label()).isEqualTo("sandbox generated 123456789 valid 02/08/2026 11:59:00 PM");
        assertThat(again.ewayBillNo()).isEqualTo("123456789");
        assertThat(documents.findById(saleId).orElseThrow().getTotalPaise()).isEqualTo(Paise.fromRupees(1000));
        verify(gateway, times(1)).generate(anyString());
        verify(gateway, never()).lookup(anyString(), anyString());

        mockMvc.perform(post("/api/invoices/" + saleId + "/eway-draft")
                        .param("userId", shop.userId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(details("UP80AB1234"))))
                .andExpect(status().isUnauthorized());
        String body = mockMvc.perform(post("/api/invoices/" + saleId + "/eway-draft")
                        .header("Authorization", "Bearer " + OwnerTokens.sign("test-owner-secret", shop.userId(), Instant.now().plus(1, ChronoUnit.HOURS)))
                        .param("userId", shop.userId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(details("UP80AB1234"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("CONFIRMED").contains("sandbox");
        assertThat(mapper.readTree(body).get("ewayBillNo").asText()).isEqualTo("123456789");
    }

    @Test
    void aResponseWithoutAnOfficialNumberIsNotLabeledGenerated() {
        Shop shop = shop();
        UUID draftId = approved(shop);
        when(gateway.generate(anyString())).thenReturn(new EwayReply("1", null, null, null, null, true));

        EwayDraftView view = book.submit(shop.userId(), draftId);

        assertThat(view.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(view.ewayBillNo()).isNull();
        assertThat(view.label()).doesNotContain("generated");
        assertThat(book.label(shop.userId(), draftId, true)).isEqualTo("local copy");
    }

    @Test
    void rejectionKeepsTheInvoiceAndACorrectedDraftNeedsApprovalAgain() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        long total = documents.findById(saleId).orElseThrow().getTotalPaise();
        UUID draftId = book.prepare(shop.userId(), saleId, details("UP80AB1234"));
        book.approve(shop.userId(), draftId);
        when(gateway.generate(anyString())).thenReturn(new EwayReply("0", null, null, null, "217", true));

        EwayDraftView rejected = book.submit(shop.userId(), draftId);

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.error()).isEqualTo("217");
        assertThat(rejected.ewayBillNo()).isNull();
        assertThat(rejected.label()).doesNotContain("generated");
        assertThat(book.label(shop.userId(), draftId, true)).isEqualTo("local copy");
        assertThat(documents.findById(saleId).orElseThrow().getTotalPaise()).isEqualTo(total);
        assertThatThrownBy(() -> book.submit(shop.userId(), draftId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This approval is no longer current.");
        verify(gateway, times(1)).generate(anyString());

        book.prepare(shop.userId(), saleId, details("UP80AB9999"));
        assertThatThrownBy(() -> book.submit(shop.userId(), draftId))
                .hasMessage("This approval is no longer current.");
        book.approve(shop.userId(), draftId);
        when(gateway.generate(anyString())).thenReturn(ACCEPTED);
        assertThat(book.submit(shop.userId(), draftId).ewayBillNo()).isEqualTo("123456789");
        verify(gateway, times(2)).generate(anyString());
    }

    @Test
    void anUnknownOutcomeIsLookedUpAndNotSubmittedAgain() {
        Shop shop = shop();
        UUID draftId = approved(shop);
        when(gateway.generate(anyString())).thenThrow(new IllegalStateException("timeout"));

        EwayDraftView unknown = book.submit(shop.userId(), draftId);

        assertThat(unknown.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(unknown.ewayBillNo()).isNull();
        when(gateway.lookup("INV", "1")).thenReturn(ACCEPTED);

        EwayDraftView found = book.submit(shop.userId(), draftId);

        assertThat(found.status()).isEqualTo("CONFIRMED");
        assertThat(found.ewayBillNo()).isEqualTo("123456789");
        verify(gateway, times(1)).generate(anyString());
        verify(gateway, times(1)).lookup("INV", "1");
    }

    @Test
    void anEditClearsApprovalBeforeAnythingIsSent() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        UUID draftId = book.prepare(shop.userId(), saleId, details("UP80AB1234"));
        book.approve(shop.userId(), draftId);
        book.prepare(shop.userId(), saleId, details("UP80AB9999"));

        assertThat(book.view(shop.userId(), draftId).status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(book.view(shop.userId(), draftId).version()).isEqualTo(2);
        assertThatThrownBy(() -> book.submit(shop.userId(), draftId))
                .hasMessage("This approval is no longer current.");
        verifyNoInteractions(gateway);
    }

    @Test
    void missingTaxBlocksApprovalAndDoesNotCallThePortal() {
        Shop shop = shop();
        UUID draftId = book.prepare(shop.userId(), sell(shop), details("UP80AB1234", null));

        EwayDraftView blocked = book.view(shop.userId(), draftId);

        assertThat(blocked.status()).isEqualTo("VALIDATION_BLOCKED");
        assertThat(blocked.error()).contains("tax");
        assertThat(blocked.label()).doesNotContain("generated");
        assertThatThrownBy(() -> book.approve(shop.userId(), draftId))
                .hasMessage("Ask for the missing e-way fields.");
        verifyNoInteractions(gateway);
    }

    @Test
    void theAssistantMayPrepareAnEwayDraftAndCannotSubmitIt() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        when(model.complete("is invoice ka e-way bill banao")).thenReturn("""
                {"intent":null,"business_id":"%s","invoice_id":"%s","eway_bill_no":"999","tot_inv_value":1,\
                "supply_type":"O","sub_supply_type":"1","from_gstin":"29ABCDE8755F1Z2","from_name":"City Stores",\
                "from_address":"Sadar Bazaar","from_place":"Agra","from_pincode":282001,"from_state_code":9,\
                "from_actual_state_code":9,"to_gstin":"09AABCU9603R1ZM","to_name":"Agarwal","to_address":"Belanganj",\
                "to_place":"Agra","to_pincode":282002,"to_state_code":9,"to_actual_state_code":9,\
                "transaction_type":1,"distance":"12","trans_mode":"1","vehicle_no":"UP80AB1234","hsn_code":392329,\
                "qty_unit":"BOX","taxable_paise":84746,"cgst_paise":7627,"sgst_paise":7627,"igst_paise":0,\
                "cess_paise":0,"cgst_rate":9,"sgst_rate":9,"igst_rate":0,"cess_rate":0,\
                "requested_tools":["prepare_eway_bill","submit_approved_eway_bill"]}
                """.formatted(UUID.randomUUID(), saleId));

        InterpretationOutcome outcome = interpreter.interpret(shop.userId(), "is invoice ka e-way bill banao", DAY, AT);

        assertThat(outcome.draft()).isNull();
        assertThat(outcome.refusedTools()).containsExactly("submit_approved_eway_bill");
        EwayDraftView prepared = book.view(shop.userId(), outcome.ewayDraftId());
        assertThat(prepared.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(prepared.environment()).isEqualTo("sandbox");
        assertThat(prepared.ewayBillNo()).isNull();
        assertThat(drafts.findById(outcome.ewayDraftId()).orElseThrow().getBusinessId()).isEqualTo(shop.businessId());
        assertThat(drafts.findById(outcome.ewayDraftId()).orElseThrow().getPayload())
                .contains("1000.00")
                .doesNotContain("999");
        verifyNoInteractions(gateway);
    }

    @Test
    void anotherBusinessCannotPrepareOrCancelTheOfficialBill() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        UUID draftId = approved(shop, saleId);
        when(gateway.generate(anyString())).thenReturn(ACCEPTED);
        book.submit(shop.userId(), draftId);
        Shop other = shop();

        assertThatThrownBy(() -> book.prepare(other.userId(), saleId, details("UP80AB1234")))
                .hasMessage("This invoice is not in the selected business.");
        assertThatThrownBy(() -> book.cancel(shop.userId(), draftId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorized operator");
        assertThat(book.view(shop.userId(), draftId).ewayBillNo()).isEqualTo("123456789");
    }

    private UUID approved(Shop shop) {
        return approved(shop, sell(shop));
    }

    private UUID approved(Shop shop, UUID saleId) {
        UUID draftId = book.prepare(shop.userId(), saleId, details("UP80AB1234"));
        book.approve(shop.userId(), draftId);
        return draftId;
    }

    private UUID sell(Shop shop) {
        openBoxes(shop);
        return posting.sell(
                shop.userId(),
                UUID.randomUUID(),
                shop.customerId(),
                shop.productId(),
                shop.godownId(),
                BigDecimal.ONE,
                "box",
                Paise.fromRupees(1000),
                DAY,
                AT
        );
    }

    private void openBoxes(Shop shop) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "eway");
        counts.propose(shop.userId(), sessionId, shop.productId(), new BigDecimal("5"), "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private EwayDetails details(String vehicle) {
        return details(vehicle, new EwayTax(
                84_746L,
                7_627L,
                7_627L,
                0L,
                0L,
                new BigDecimal("9"),
                new BigDecimal("9"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));
    }

    private EwayDetails details(String vehicle, EwayTax tax) {
        return new EwayDetails(
                "O",
                "1",
                new EwayParty("29ABCDE8755F1Z2", "City Stores", "Sadar Bazaar", "Agra", 282001, 9, 9),
                new EwayParty("09AABCU9603R1ZM", "Agarwal", "Belanganj", "Agra", 282002, 9, 9),
                1,
                "12",
                "1",
                vehicle,
                null,
                392_329L,
                "BOX",
                tax
        );
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "EWAY-" + userId.toString().substring(0, 8),
                "Office folder",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
}
