package in.munimsaathi.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WhatsAppMessageSenderTest {

    @Test
    void anAcceptedProviderReplyIsStoredAsAccepted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v20.0/phone-1/messages"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.real\"}]}", MediaType.APPLICATION_JSON));
        WhatsAppMessageSender sender = new WhatsAppMessageSender(properties("token"), builder, new ObjectMapper());

        ProviderReceipt receipt = sender.send("919876543210", "payment_due", "payment reminder");

        assertThat(receipt.status()).isEqualTo("accepted");
        assertThat(receipt.providerId()).isEqualTo("wamid.real");
        server.verify();
    }

    @Test
    void aProviderErrorIsStoredAsFailed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v20.0/phone-1/messages"))
                .andRespond(withSuccess("{\"error\":{\"message\":\"template\"}}", MediaType.APPLICATION_JSON));
        WhatsAppMessageSender sender = new WhatsAppMessageSender(properties("token"), builder, new ObjectMapper());

        assertThat(sender.send("919876543210", "payment_due", "payment reminder").status()).isEqualTo("failed");
        server.verify();
    }

    @Test
    void aMissingTokenDoesNotCallTheProvider() {
        WhatsAppMessageSender sender = new WhatsAppMessageSender(properties(""), RestClient.builder(), new ObjectMapper());

        ProviderReceipt receipt = sender.send("919876543210", "payment_due", "payment reminder");

        assertThat(receipt.status()).isEqualTo("blocked");
        assertThat(receipt.providerId()).isNull();
    }

    private static MunimProperties properties(String token) {
        return new MunimProperties(
                "Asia/Kolkata",
                "INR",
                null,
                null,
                new MunimProperties.Whatsapp(token, "", "", "phone-1", "", "v20.0", false, "", ""),
                null,
                null,
                new MunimProperties.Reminders(15)
        );
    }
}
