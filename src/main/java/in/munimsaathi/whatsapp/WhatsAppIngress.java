package in.munimsaathi.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.draft.DraftProposal;
import in.munimsaathi.interpretation.CommandInterpreter;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class WhatsAppIngress {

    private final MunimProperties properties;
    private final ObjectMapper mapper;
    private final TenantContext tenants;
    private final WhatsAppSenderRepository senders;
    private final InboundMessageRepository messages;
    private final CommandInterpreter interpreter;

    public WhatsAppIngress(
            MunimProperties properties,
            ObjectMapper mapper,
            TenantContext tenants,
            WhatsAppSenderRepository senders,
            InboundMessageRepository messages,
            CommandInterpreter interpreter
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.tenants = tenants;
        this.senders = senders;
        this.messages = messages;
        this.interpreter = interpreter;
    }

    @Transactional
    public void allow(UUID userId, String phone) {
        UUID businessId = tenants.currentBusinessId(userId);
        if (senders.findByPhone(phone).isEmpty()) {
            senders.save(new WhatsAppSender(UUID.randomUUID(), businessId, userId, phone));
        }
    }

    public boolean challengeMatches(String mode, String token) {
        String expected = properties.whatsapp() == null ? null : properties.whatsapp().verifyToken();
        return "subscribe".equals(mode) && expected != null && !expected.isBlank() && constantEquals(expected, token);
    }

    @Transactional
    public WebhookResult receive(String signature, byte[] body) {
        if (!signed(signature, body)) {
            return new WebhookResult(401, null);
        }
        JsonNode value;
        try {
            value = mapper.readTree(body).path("entry").path(0).path("changes").path(0).path("value");
        } catch (Exception exception) {
            return new WebhookResult(400, null);
        }
        JsonNode incoming = value.path("messages");
        if (incoming.isArray() && !incoming.isEmpty()) {
            return storeMessage(incoming.get(0));
        }
        JsonNode statuses = value.path("statuses");
        if (statuses.isArray() && !statuses.isEmpty()) {
            String statusId = "status:" + statuses.get(0).path("id").asText();
            return store(null, null, statusId, null, null, null, null, InboundStatus.STATUS_CALLBACK, 200);
        }
        return new WebhookResult(200, null);
    }

    @Transactional
    public int openDrafts(LocalDate occurredOn, Instant occurredAt) {
        int opened = 0;
        for (InboundMessage message : messages.findByStatusAndDraftIdIsNull(InboundStatus.STORED)) {
            if (message.getBody() == null || message.getUserId() == null) {
                continue;
            }
            DraftProposal draft = interpreter.interpret(message.getUserId(), message.getBody(), occurredOn, occurredAt).draft();
            if (draft != null) {
                message.attachDraft(draft.id());
                opened = opened + 1;
            }
        }
        return opened;
    }

    private WebhookResult storeMessage(JsonNode message) {
        String messageId = text(message, "id");
        String phone = text(message, "from");
        JsonNode media = media(message);
        String body = textBody(message, media);
        String mediaId = media == null ? null : text(media, "id");
        String mediaType = media == null ? null : text(media, "mime_type");
        if (messageId == null) {
            return new WebhookResult(400, null);
        }
        WhatsAppSender sender = phone == null ? null : senders.findByPhone(phone).orElse(null);
        if (sender == null) {
            return store(null, null, messageId, phone, body, mediaId, mediaType, InboundStatus.REJECTED, 403);
        }
        return store(sender.getBusinessId(), sender.getUserId(), messageId, phone, body, mediaId, mediaType, InboundStatus.STORED, 200);
    }

    private WebhookResult store(
            UUID businessId,
            UUID userId,
            String messageId,
            String phone,
            String body,
            String mediaId,
            String mediaType,
            InboundStatus status,
            int httpStatus
    ) {
        InboundMessage existing = messages.findByMessageId(messageId).orElse(null);
        if (existing != null) {
            int code = existing.getStatus() == InboundStatus.REJECTED ? 403 : 200;
            return new WebhookResult(code, existing.getId());
        }
        InboundMessage saved = new InboundMessage(
                UUID.randomUUID(),
                businessId,
                userId,
                messageId,
                phone,
                body,
                status
        );
        saved.attachMedia(mediaId, mediaType);
        messages.save(saved);
        return new WebhookResult(httpStatus, saved.getId());
    }

    private static JsonNode media(JsonNode message) {
        for (String field : new String[] {"audio", "image", "document", "video"}) {
            JsonNode node = message.get(field);
            if (node != null && !node.isNull() && text(node, "id") != null) {
                return node;
            }
        }
        return null;
    }

    private static String textBody(JsonNode message, JsonNode media) {
        String body = message.path("text").path("body").asText(null);
        if (body != null && !body.isBlank()) {
            return body;
        }
        if (media == null) {
            return null;
        }
        String caption = text(media, "caption");
        return caption;
    }

    private boolean signed(String signature, byte[] body) {
        String secret = properties.whatsapp() == null ? null : properties.whatsapp().appSecret();
        if (secret == null || secret.isBlank() || signature == null || !signature.startsWith("sha256=") || body == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signature.substring("sha256=".length()));
            return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean constantEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    public record WebhookResult(int status, UUID id) {
    }
}
