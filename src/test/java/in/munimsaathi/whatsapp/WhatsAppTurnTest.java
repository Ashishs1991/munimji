package in.munimsaathi.whatsapp;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.dashboard.OwnerDashboard;
import in.munimsaathi.draft.Draft;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.jobs.Job;
import in.munimsaathi.jobs.JobRepository;
import in.munimsaathi.reminder.ReminderBook;
import in.munimsaathi.reminder.ReminderKind;
import in.munimsaathi.tenant.TenantContext;
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
import java.time.LocalTime;
import java.time.ZoneId;
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

    @Autowired
    private TenantContext tenants;

    @Autowired
    private ReminderBook reminders;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private OwnerDashboard dashboard;

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
        verify(session).sendText(PHONE, "Namaste! Maal aaya, bika, paise mile ya diye? Bataiye.");
    }

    @Test
    void yesRepliesFromTheBooksAndDoesNotAskTheModel() {
        UUID userId = owner();
        ingress.allow(userId, PHONE);
        ingress.receive(sign(message("wamid.yes", PHONE, "Yes")), message("wamid.yes", PHONE, "Yes"));
        when(session.sendText(eq(PHONE), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        verify(model, never()).complete(anyString());
        verify(session).sendText(PHONE, "Abhi kis entry ko confirm karna hai? Ek saaf pending entry honi chahiye.");
        assertThat(messages.findByMessageId("wamid.yes").orElseThrow().getStatus()).isEqualTo(InboundStatus.REPLIED);
    }

    @Test
    void yesApprovesTheShownEntryEvenWithOlderPendingWorkAndDoesNotRepeatPost() {
        String phone = "919800005551";
        Shelf sh = shelf(phone);
        LocalDate day = LocalDate.now();
        Instant at = Instant.now();
        var older = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 100_000L, day, at);
        var current = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 200_000L, day, at);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "choices-reply"));
        byte[] ambiguous = message("old-proposals-yes", phone, "Yes");
        ingress.receive(sign(ambiguous), ambiguous);
        turn.openStored();
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("entry " + current.id()));
        byte[] show = message("shown-entry", phone, "entry " + current.id());
        ingress.receive(sign(show), show);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "shown-reply"));
        turn.openStored();

        for (String id : new String[]{"shown-yes-1", "shown-yes-2"}) {
            byte[] yes = message(id, phone, "Yes");
            ingress.receive(sign(yes), yes);
            turn.openStored();
        }

        assertThat(draftRows.findById(current.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.POSTED);
        assertThat(draftRows.findById(older.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(counts.balance(sh.userId(), sh.productId(), sh.godownId())).isEqualByComparingTo("40");
        verify(model, never()).complete(anyString());
    }

    @Test
    void shownProposalCannotApproveAnEditThatWasNotShown() {
        String phone = "919800005552";
        Shelf sh = shelf(phone);
        LocalDate day = LocalDate.now();
        Instant at = Instant.now();
        var proposal = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 100_000L, day, at);
        byte[] show = message("shown-before-edit", phone, "show entry");
        ingress.receive(sign(show), show);
        InboundMessage presentation = messages.findByMessageId("shown-before-edit").orElseThrow();
        presentation.attachDraft(proposal.id());
        messages.saveAndFlush(presentation);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "shown-reply"));
        turn.openStored();
        drafts.edit(sh.userId(), proposal.id(), "sale", sh.customerId(), sh.productId(), sh.godownId(), new BigDecimal("2"), "box", 100_000L, day, at);
        byte[] yes = message("shown-stale-yes", phone, "Yes");
        ingress.receive(sign(yes), yes);
        turn.openStored();
        assertThat(counts.balance(sh.userId(), sh.productId(), sh.godownId())).isEqualByComparingTo("50");
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("This approval is no longer current."));
    }

    @Test
    void shownProposalLookupIsScopedToBusinessActorAndPhone() {
        String phone = "919800005553";
        Shelf sh = shelf(phone);
        var proposal = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 100_000L, LocalDate.now(), Instant.now());
        InboundMessage shown = new InboundMessage(UUID.randomUUID(), sh.businessId(), sh.userId(), "scoped-presentation", phone, "show", InboundStatus.STORED);
        shown.attachDraft(proposal.id());
        shown.rememberShownProposal(proposal.version(), proposal.contentHash());
        messages.saveAndFlush(shown);
        assertThat(messages.latestShownProposal(sh.businessId(), sh.userId(), phone)).isEmpty();
        shown.markReplied();
        messages.saveAndFlush(shown);
        assertThat(messages.latestShownProposal(sh.businessId(), sh.userId(), phone)).isPresent();
        assertThat(messages.latestShownProposal(UUID.randomUUID(), sh.userId(), phone)).isEmpty();
        assertThat(messages.latestShownProposal(sh.businessId(), UUID.randomUUID(), phone)).isEmpty();
        assertThat(messages.latestShownProposal(sh.businessId(), sh.userId(), "919800005554")).isEmpty();
    }

    @Test
    void outgoingLoanCanBeConfirmedWhileAnOlderSaleIsPending() {
        String phone = "919800005555";
        Shelf sh = shelf(phone);
        var old = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 100_000L, LocalDate.now(), Instant.now());
        String instruction = "Agarwal ko das hazaar cash udhaar diye";
        when(model.complete(instruction)).thenReturn("""
                {"turn":"new_action","action":"outgoing_payment","party_name":"Agarwal","amount_paise":1000000,"payment_purpose":"loan","payment_method":"Cash"}
                """);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "loan-shown"));
        byte[] body = message("loan-to-confirm", phone, instruction);
        ingress.receive(sign(body), body);
        turn.openStored();
        UUID loanId = messages.findByMessageId("loan-to-confirm").orElseThrow().getDraftId();
        assertThat(loanId).isNotNull().isNotEqualTo(old.id());
        byte[] yes = message("loan-confirm-yes", phone, "Yes");
        ingress.receive(sign(yes), yes);
        turn.openStored();
        assertThat(draftRows.findById(loanId).orElseThrow().getStatus()).isEqualTo(DraftStatus.POSTED);
        assertThat(draftRows.findById(old.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(counts.balance(sh.userId(), sh.productId(), sh.godownId())).isEqualByComparingTo("50");
    }

    @Test
    void latestShownProposalUsesSuccessfulSendOrderWhenAnOlderReplyRetries() {
        String phone = "919800005556";
        Shelf sh = shelf(phone);
        var proposal = drafts.propose(sh.userId(), "sale", sh.customerId(), sh.productId(), sh.godownId(), BigDecimal.ONE, "box", 100_000L, LocalDate.now(), Instant.now());
        InboundMessage first = new InboundMessage(UUID.randomUUID(), sh.businessId(), sh.userId(), "first-delivery", phone, "first", InboundStatus.STORED);
        first.attachDraft(proposal.id());
        first.rememberShownProposal(proposal.version(), proposal.contentHash());
        messages.saveAndFlush(first);
        InboundMessage second = new InboundMessage(UUID.randomUUID(), sh.businessId(), sh.userId(), "second-delivery", phone, "second", InboundStatus.STORED);
        second.attachDraft(proposal.id());
        second.rememberShownProposal(proposal.version(), proposal.contentHash());
        second.recordAttempt("accepted");
        messages.saveAndFlush(second);
        assertThat(messages.latestShownProposal(sh.businessId(), sh.userId(), phone).orElseThrow().getId()).isEqualTo(second.getId());
        first.recordAttempt("accepted");
        messages.saveAndFlush(first);
        assertThat(messages.latestShownProposal(sh.businessId(), sh.userId(), phone).orElseThrow().getId()).isEqualTo(first.getId());
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

        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Abhi yeh batana hai: godown"));
        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Is entry ko confirm karne ke liye Haan ya Yes bhejiye"));
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

    @Test
    void yesKeepsTheVersionTheOwnerWasShown() {
        String phone = "919800000120";
        Shelf shelf = shelf(phone);
        LocalDate day = LocalDate.of(2026, 8, 1);
        Instant at = Instant.parse("2026-08-01T04:30:00Z");
        var proposal = drafts.propose(
                shelf.userId(), "sale", shelf.customerId(), shelf.productId(), shelf.godownId(),
                BigDecimal.ONE, "box", 100_000L, day, at
        );
        Draft shown = draftRows.findById(proposal.id()).orElseThrow();
        shown.present();
        draftRows.save(shown);
        drafts.edit(
                shelf.userId(), proposal.id(), "sale", shelf.customerId(), shelf.productId(), shelf.godownId(),
                new BigDecimal("2"), "box", 100_000L, day, at
        );
        ingress.receive(sign(message("wamid.stale-yes", phone, "Yes")), message("wamid.stale-yes", phone, "Yes"));
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("This approval is no longer current."));
        assertThat(counts.balance(shelf.userId(), shelf.productId(), shelf.godownId())).isEqualByComparingTo("50");
        assertThat(draftRows.findById(proposal.id()).orElseThrow().getStatus()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
    }

    @Test
    void aStoredMessageStaysInTheBusinessItArrivedFor() {
        String phone = "919800000118";
        Shelf shelf = shelf(phone);
        ingress.receive(
                sign(message("wamid.business", phone, "Agarwal ko ek box")),
                message("wamid.business", phone, "Agarwal ko ek box")
        );
        Business other = businesses.save(new Business(UUID.randomUUID(), "Other House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), other.getId(), shelf.userId(), MembershipRole.OWNER));
        tenants.selectBusiness(shelf.userId(), other.getId());
        when(model.complete("Agarwal ko ek box")).thenReturn("""
                {"intent":"sale","party_name":"Agarwal","product_name":"Office folder","quantity":"1","unit":"box","amount_paise":100000,"location_code":"g1","requested_tools":[]}
                """);
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        assertThat(draftRows.findAll().stream().filter(draft -> shelf.businessId().equals(draft.getBusinessId())).toList()).hasSize(1);
        assertThat(draftRows.findAll().stream().filter(draft -> other.getId().equals(draft.getBusinessId())).toList()).isEmpty();
        assertThat(dashboard.overview(shelf.userId(), LocalDate.of(2026, 8, 1)).businessName()).isEqualTo("Other House");
    }

    @Test
    void yesApprovesAnOwnerReminderAndTheDashboardShowsIt() {
        String phone = "919800000119";
        Shelf shelf = shelf(phone);
        LocalDate when = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        UUID taskId = reminders.propose(
                shelf.userId(),
                ReminderKind.OWNER_REMINDER,
                null,
                null,
                null,
                when,
                LocalTime.of(10, 0),
                "owner reminder",
                false,
                null,
                true
        );
        ingress.receive(sign(message("wamid.remind", phone, "Yes")), message("wamid.remind", phone, "Yes"));
        when(session.sendText(eq(phone), anyString())).thenReturn(new ProviderReceipt("accepted", "wamid.out"));

        turn.openStored();

        verify(session).sendText(eq(phone), org.mockito.ArgumentMatchers.contains("Reminder confirm ho gaya"));
        assertThat(dashboard.overview(shelf.userId(), LocalDate.of(2026, 8, 1)).ownerReminders())
                .anyMatch(text -> text.contains("SCHEDULED") && text.contains("owner reminder"));
        Job job = jobs.findAll().stream()
                .filter(item -> taskId.toString().equals(item.getPayload()))
                .findFirst()
                .orElseThrow();
        job.lease("owner-test", Instant.now().plusSeconds(60));
        jobs.saveAndFlush(job);
        reminders.dispatch(job, "owner-test", Instant.now());
        assertThat(dashboard.overview(shelf.userId(), LocalDate.of(2026, 8, 1)).ownerReminders())
                .anyMatch(text -> text.contains("DASHBOARD") && text.contains("owner reminder"));
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
    @Autowired private in.munimsaathi.interpretation.ConversationMemory reviewMemory;
    @Test void reviewVoiceTranscriptMustBeSavedAsOwnerTurn() {
        String phone="919800008888"; Shelf sh=shelf(phone);
        String json="""
          {"entry":[{"changes":[{"value":{"messages":[{"id":"review-memory-audio","from":"%s","audio":{"id":"review-audio","mime_type":"audio/ogg"}}]}}]}]}
          """.formatted(phone);
        byte[] b=json.getBytes(StandardCharsets.UTF_8); ingress.receive(sign(b),b);
        when(session.download("review-audio")).thenReturn(new byte[]{1,2,3});
        when(transcriber.transcribe(any(),eq("audio/ogg"))).thenReturn("Agarwal ka balance");
        when(model.complete("Agarwal ka balance")).thenReturn("{\"action\":\"party_balance\",\"party_name\":\"Agarwal\"}");
        when(session.sendText(eq(phone),anyString())).thenReturn(new ProviderReceipt("accepted","review-response"));
        turn.openStored();
        assertThat(reviewMemory.recent(sh.userId())).contains("Agarwal ka balance");
    }

    @Test void paymentConfirmationExplainsDirectionAndStockEffect() {
        String phone="919800007777"; Shelf sh=shelf(phone);
        byte[] body=message("money-effects",phone,"Agarwal paid 1000 cash"); ingress.receive(sign(body),body);
        when(model.complete("Agarwal paid 1000 cash")).thenReturn("""
          {"turn":"new_action","action":"customer_payment_received","party_name":"Agarwal","amount_paise":100000,"payment_method":"Cash"}
          """);
        when(session.sendText(eq(phone),anyString())).thenReturn(new ProviderReceipt("accepted","money-effects-reply"));
        turn.openStored();
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Paise mile hain — Agarwal"));
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Unka bakaya itna ghatega: ₹1000.00"));
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Stock mein koi badlav nahi hoga"));
        assertThat(counts.balance(sh.userId(),sh.productId(),sh.godownId())).isEqualByComparingTo("50");
    }
    @Test void loanAndPayableConfirmationsSayWhoOwesWhom() {
        for(boolean payable: new boolean[]{false,true}) {
            String phone=payable?"919800007778":"919800007779"; Shelf sh=shelf(phone);
            String name=payable?"Bags Co":"Agarwal";
            if(payable) parties.create(sh.userId(),name,PartyRole.SUPPLIER);
            String instruction=payable?"supplier obligation":"new loan";
            byte[] body=message("direction-"+phone,phone,instruction); ingress.receive(sign(body),body);
            when(model.complete(instruction)).thenReturn("{\"turn\":\"new_action\",\"action\":\""+(payable?"supplier_payable":"standalone_receivable")+"\",\"party_name\":\""+name+"\",\"amount_paise\":100000,\"cause\":\"new\"}");
            when(session.sendText(eq(phone),anyString())).thenReturn(new ProviderReceipt("accepted","direction-reply"));
            turn.openStored();
            verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains(payable?"aapko dene hain — Bags Co ₹1000.00":"Agarwal se aapko lene hain ₹1000.00"));
            verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Stock mein koi badlav nahi hoga"));
        }
    }

    @Autowired private in.munimsaathi.interpretation.PendingBalanceQuestions reviewQuestions;
    @Test void bareCancelClearsThePendingBalanceQuestion() {
        String phone="919800006666"; Shelf sh=shelf(phone);
        reviewQuestions.save(sh.userId(),"Anisha","payable",500000L);
        byte[] body=message("cancel-balance-question",phone,"cancel"); ingress.receive(sign(body),body);
        when(session.sendText(eq(phone),anyString())).thenReturn(new ProviderReceipt("accepted","canceled-question"));
        turn.openStored();
        assertThat(reviewQuestions.get(sh.userId())).isEmpty();
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Balance wala sawal cancel ho gaya"));
    }

    @Test void outgoingPaymentAsksPurposeInHinglishAndRetainsAmount() {
        String phone="919800005555"; Shelf sh=shelf(phone);
        parties.create(sh.userId(),"Anisha Gajak",PartyRole.SUPPLIER);
        byte[] first=message("anisha-paid",phone,"maine Anisha ko panch hajar diye"); ingress.receive(sign(first),first);
        when(model.complete("maine Anisha ko panch hajar diye")).thenReturn("""
          {"turn":"new_action","action":"outgoing_payment","party_name":"Anisha Gajak","amount_paise":500000}
          """);
        when(session.sendText(eq(phone),anyString())).thenReturn(new ProviderReceipt("accepted","anisha-paid-response"));
        turn.openStored();
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("aapne Anisha Gajak ko ₹5000.00 diye"));
        verify(session).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("Purane bill ka bakaya chukaya, advance diya, ya udhaar diya?"));
        verify(session,never()).sendText(eq(phone),org.mockito.ArgumentMatchers.contains("you owe"));
        byte[] amount=message("anisha-repeat-amount",phone,"5000"); ingress.receive(sign(amount),amount);
        when(model.complete("5000")).thenReturn("{\"turn\":\"new_action\",\"amount_paise\":500000}");
        turn.openStored();
        assertThat(draftRows.findByBusinessIdAndStatus(sh.businessId(),in.munimsaathi.draft.DraftStatus.NEEDS_CLARIFICATION)).hasSize(1);
        assertThat(draftRows.findByBusinessIdAndStatus(sh.businessId(),in.munimsaathi.draft.DraftStatus.NEEDS_CLARIFICATION).get(0).getKind()).isEqualTo("OUTGOING_PAYMENT");
    }

}
