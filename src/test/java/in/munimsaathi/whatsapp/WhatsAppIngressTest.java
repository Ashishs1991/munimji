package in.munimsaathi.whatsapp;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WhatsAppIngressTest {

    private static final String SECRET = "test-secret";
    private static final String PHONE = "919800000000";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
        registry.add("munim.whatsapp.app-secret", () -> SECRET);
        registry.add("munim.whatsapp.verify-token", () -> "verify-me");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private WhatsAppIngress ingress;

    @Autowired
    private InboundMessageRepository messages;

    @Autowired
    private DraftRepository drafts;

    @MockBean
    private LanguageModel model;

    @Test
    void aSignedWebhookIsStoredAndAcknowledgedBeforeAnyModelCall() throws Exception {
        UUID userId = owner();
        ingress.allow(userId, PHONE);
        byte[] body = message("wamid.known", PHONE, "ek box");

        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(messages.countByMessageId("wamid.known")).isEqualTo(1);
        assertThat(messages.findByMessageId("wamid.known").orElseThrow().getStatus()).isEqualTo(InboundStatus.STORED);
        verify(model, never()).complete(anyString());
    }

    @Test
    void anUnknownSenderIsRejectedAndADuplicateDoesNotOpenASecondDraft() throws Exception {
        byte[] unknown = message("wamid.unknown", "910000000000", "ek box");
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(unknown))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknown))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(unknown))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknown))
                .andExpect(status().isForbidden());
        assertThat(messages.countByMessageId("wamid.unknown")).isEqualTo(1);
        assertThat(messages.findByMessageId("wamid.unknown").orElseThrow().getStatus()).isEqualTo(InboundStatus.REJECTED);

        UUID userId = owner();
        ingress.allow(userId, PHONE);
        byte[] known = message("wamid.once", PHONE, "ek box once");
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(known))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(known))
                .andExpect(status().isOk());
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(known))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(known))
                .andExpect(status().isOk());
        assertThat(messages.countByMessageId("wamid.once")).isEqualTo(1);

        when(model.complete(anyString())).thenReturn("""
                {"intent":"sale","quantity":"1","requested_tools":[]}
                """);
        verify(model, never()).complete(anyString());
        ingress.openDrafts(LocalDate.of(2026, 10, 31), Instant.parse("2026-10-31T04:30:00Z"));
        UUID draftId = messages.findByMessageId("wamid.once").orElseThrow().getDraftId();
        ingress.openDrafts(LocalDate.of(2026, 10, 31), Instant.parse("2026-10-31T04:30:00Z"));

        UUID businessId = messages.findByMessageId("wamid.once").orElseThrow().getBusinessId();
        List<?> open = drafts.findByBusinessIdAndStatusIn(
                businessId,
                List.of(DraftStatus.NEEDS_CLARIFICATION, DraftStatus.AWAITING_APPROVAL)
        );
        assertThat(open).hasSize(1);
        assertThat(messages.findByMessageId("wamid.once").orElseThrow().getDraftId()).isEqualTo(draftId);
        verify(model, times(1)).complete("ek box once");
    }

    @Test
    void twoDeliveriesOfTheSameMessageStayOneRow() throws Exception {
        UUID userId = owner();
        ingress.allow(userId, PHONE);
        byte[] body = message("wamid.race", PHONE, "Hi");
        String signature = sign(body);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return ingress.receive(signature, body).status();
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return ingress.receive(signature, body).status();
            });
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsOnly(200);
        } finally {
            pool.shutdown();
        }
        assertThat(messages.countByMessageId("wamid.race")).isEqualTo(1);
    }

    @Test
    void aBadSignatureIsNotStoredAndAStatusCallbackIsNotACommand() throws Exception {
        byte[] body = message("wamid.bad", PHONE, "ek box");
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", "sha256=00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        assertThat(messages.countByMessageId("wamid.bad")).isZero();

        byte[] status = """
                {"entry":[{"changes":[{"value":{"statuses":[{"id":"wamid.status","status":"delivered"}]}}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/whatsapp")
                        .header("X-Hub-Signature-256", sign(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(status))
                .andExpect(status().isOk());
        assertThat(messages.findByMessageId("status:wamid.status").orElseThrow().getStatus())
                .isEqualTo(InboundStatus.STATUS_CALLBACK);

        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verify-me")
                        .param("hub.challenge", "challenge-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-token"));
        verify(model, never()).complete(anyString());
    }

    private UUID owner() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        return userId;
    }

    private static byte[] message(String id, String from, String text) {
        String json = """
                {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","id":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(from, id, text);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
