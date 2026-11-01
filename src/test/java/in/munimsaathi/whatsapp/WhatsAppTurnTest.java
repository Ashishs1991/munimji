package in.munimsaathi.whatsapp;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.interpretation.Transcriber;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.reminder.ProviderReceipt;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class WhatsAppTurnTest {

    private static final String SECRET = "test-secret";
    private static final String PHONE = "919800000111";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
        registry.add("munim.whatsapp.app-secret", () -> SECRET);
        registry.add("munim.whatsapp.verify-token", () -> "verify-me");
        registry.add("munim.whatsapp.auto-reply", () -> "false");
    }

    @Autowired
    private WhatsAppIngress ingress;

    @Autowired
    private WhatsAppTurn turn;

    @Autowired
    private InboundMessageRepository messages;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @MockBean
    private LanguageModel model;

    @MockBean
    private Transcriber transcriber;

    @MockBean
    private SessionReply session;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Autowired
    private GodownRepository godowns;

    @Autowired
    private CountService counts;

    @Autowired
    private DraftBook drafts;

    @Autowired
    private DraftRepository draftRows;

    @Test
    void aStoredMessageBecomesOneReplyAndASecondPassDoesNotCallTheModelAgain() {
        UUID userId = owner();
        ingress.allow(userId, PHONE);
        ingress.receive(sign(message("wamid.turn", PHONE, "ek box")), message("wamid.turn", PHONE, "ek box"));
        when(model.complete("ek box")).thenReturn("""
                {"intent":null,"requested_tools":[]}
                """);
        when(session.sendText(eq(PHONE), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();
        turn.openStored();

        assertThat(messages.findByMessageId("wamid.turn").orElseThrow().getStatus()).isEqualTo(InboundStatus.REPLIED);
        verify(model, times(1)).complete("ek box");
        verify(session).sendText(PHONE, "I could not make a draft from that. Say the party, product, quantity, unit, and price.");
    }

    @Test
    void yesRepliesFromTheBooksAndDoesNotAskTheModel() {
        UUID userId = owner();
        ingress.allow(userId, PHONE);
        ingress.receive(sign(message("wamid.yes", PHONE, "Yes")), message("wamid.yes", PHONE, "Yes"));
        when(session.sendText(eq(PHONE), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        verify(model, never()).complete(anyString());
        verify(session).sendText(PHONE, "Yes approves only one current proposal.");
        assertThat(messages.findByMessageId("wamid.yes").orElseThrow().getStatus()).isEqualTo(InboundStatus.REPLIED);
    }

    @Test
    void aBlockedReceiptLeavesTheMessageUnreplied() {
        UUID userId = owner();
        String phone = "919800000113";
        ingress.allow(userId, phone);
        ingress.receive(sign(message("wamid.blocked", phone, "Yes")), message("wamid.blocked", phone, "Yes"));
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("blocked", null));

        turn.openStored();

        InboundMessage saved = messages.findByMessageId("wamid.blocked").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(InboundStatus.STORED);
        assertThat(saved.getReplyStatus()).isEqualTo("blocked");
        messages.delete(saved);
    }

    @Test
    void aFailedYesRetriesTheSameDraftAndNotALaterOne() {
        String phone = "919800000114";
        Shelf shelf = shelf(phone);
        LocalDate day = LocalDate.of(2026, 8, 1);
        Instant at = Instant.parse("2026-08-01T04:30:00Z");
        drafts.propose(shelf.userId(), "sale", shelf.customerId(), shelf.productId(), shelf.godownId(), BigDecimal.ONE, "box", 100_000L, day, at);
        ingress.receive(sign(message("wamid.yes-retry", phone, "Yes")), message("wamid.yes-retry", phone, "Yes"));
        when(session.sendText(eq(phone), anyString())).thenThrow(new IllegalStateException("provider down"));

        turn.openStored();

        assertThat(messages.findByMessageId("wamid.yes-retry").orElseThrow().getStatus()).isEqualTo(InboundStatus.STORED);
        assertThat(messages.findByMessageId("wamid.yes-retry").orElseThrow().getPostedDocumentId()).isNotNull();
        assertThat(counts.balance(shelf.userId(), shelf.productId(), shelf.godownId())).isEqualByComparingTo("40");
        var later = drafts.propose(shelf.userId(), "sale", shelf.customerId(), shelf.productId(), shelf.godownId(), BigDecimal.ONE, "box", 50_000L, day, at);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        assertThat(draftRows.findById(later.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(counts.balance(shelf.userId(), shelf.productId(), shelf.godownId())).isEqualByComparingTo("40");
        messages.delete(messages.findByMessageId("wamid.yes-retry").orElseThrow());
    }

    @Test
    void aFollowUpFillsTheOpenDraftAndTheReplyShowsTheSale() {
        String phone = "919800000115";
        Shelf shelf = shelf(phone);
        when(model.complete("Agarwal ko ek box")).thenReturn("""
                {"intent":"sale","party_name":"Agarwal","product_name":"Office folder","quantity":"1","unit":"box","amount_paise":100000,"requested_tools":[]}
                """);
        when(model.complete("Godown 1")).thenReturn("""
                {"intent":null,"location_code":"g1","requested_tools":[]}
                """);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));
        ingress.receive(sign(message("wamid.follow-1", phone, "Agarwal ko ek box")), message("wamid.follow-1", phone, "Agarwal ko ek box"));

        turn.openStored();

        ingress.receive(sign(message("wamid.follow-2", phone, "Godown 1")), message("wamid.follow-2", phone, "Godown 1"));
        turn.openStored();

        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Still need location"));
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Reply Yes to post this draft only"));
        verify(session, times(2)).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Agarwal"));
        verify(session, times(2)).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Office folder"));
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Godown 1"));
        assertThat(draftRows.findAll().stream().filter(draft -> shelf.businessId().equals(draft.getBusinessId())).toList()).hasSize(1);
    }

    @Test
    void aVoiceNoteUsesTheTranscriptInsteadOfAskingForText() {
        String phone = "919800000116";
        UUID userId = owner();
        ingress.allow(userId, phone);
        String json = """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.voice","from":"%s","type":"audio","audio":{"id":"media-1","mime_type":"audio/ogg"}}]}}]}]}
                """.formatted(phone);
        ingress.receive(sign(json.getBytes(StandardCharsets.UTF_8)), json.getBytes(StandardCharsets.UTF_8));
        when(session.download("media-1")).thenReturn(new byte[] {1, 2, 3});
        when(transcriber.transcribe(any(), eq("audio/ogg"))).thenReturn("ek box");
        when(model.complete("ek box")).thenReturn("""
                {"intent":"sale","quantity":"1","unit":"box","requested_tools":[]}
                """);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        verify(transcriber).transcribe(any(), eq("audio/ogg"));
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("1 box"));
        verify(session, never()).sendText(eq(phone), eq("Send the instruction as text."));
    }

    @Test
    void aBlockedVoiceReplyDoesNotCreateASecondDraft() {
        String phone = "919800000117";
        UUID userId = owner();
        ingress.allow(userId, phone);
        String json = """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"wamid.voice-retry","from":"%s","type":"audio","audio":{"id":"media-2","mime_type":"audio/ogg"}}]}}]}]}
                """.formatted(phone);
        ingress.receive(sign(json.getBytes(StandardCharsets.UTF_8)), json.getBytes(StandardCharsets.UTF_8));
        when(session.download("media-2")).thenReturn(new byte[] {4, 5, 6});
        when(transcriber.transcribe(any(), eq("audio/ogg"))).thenReturn("ek box");
        when(model.complete("ek box")).thenReturn("""
                {"intent":"sale","quantity":"1","unit":"box","requested_tools":[]}
                """);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("blocked", null));

        turn.openStored();
        turn.openStored();

        verify(transcriber, times(1)).transcribe(any(), eq("audio/ogg"));
        UUID businessId = memberships.findByAuthUserId(userId).get(0).getBusinessId();
        assertThat(draftRows.findAll().stream().filter(draft -> businessId.equals(draft.getBusinessId())).toList()).hasSize(1);
        assertThat(messages.findByMessageId("wamid.voice-retry").orElseThrow().getStatus()).isEqualTo(InboundStatus.STORED);
    }

    private Shelf shelf(String phone) {
        UUID userId = owner();
        ingress.allow(userId, phone);
        UUID businessId = memberships.findByAuthUserId(userId).get(0).getBusinessId();
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), businessId, "g1", "Godown 1"));
        Product product = catalog.registerProduct(userId, "WA-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false);
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        UUID customerId = parties.create(userId, "Agarwal", PartyRole.CUSTOMER).getId();
        UUID sessionId = counts.start(userId, godown.getId(), Instant.parse("2026-08-01T04:30:00Z"), "whatsapp");
        counts.propose(userId, sessionId, product.getId(), new BigDecimal("5"), "box", null);
        counts.confirm(userId, sessionId, UUID.randomUUID());
        return new Shelf(userId, businessId, godown.getId(), product.getId(), customerId);
    }

    private UUID owner() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        return userId;
    }

    private static byte[] message(String id, String from, String text) {
        return """
                {"entry":[{"changes":[{"value":{"messages":[{"id":"%s","from":"%s","text":{"body":"%s"}}]}}]}]}
                """.formatted(id, from, text).getBytes(StandardCharsets.UTF_8);
    }

    private record Shelf(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
