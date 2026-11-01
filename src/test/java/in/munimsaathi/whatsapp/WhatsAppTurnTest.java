package in.munimsaathi.whatsapp;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.reminder.ProviderReceipt;
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
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    private SessionReply session;

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
