package in.munimsaathi.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WhatsAppSessionClientTest {

    @Test
    void aTextReplyUsesTheSessionWindow() {
        RestClient.Builder builder = RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v20.0/phone-1/messages"))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().string(containsString("\"type\":\"text\"")))
                .andExpect(content().string(containsString("Reply Yes to post it.")))
                .andRespond(withSuccess("""
                        {"messages":[{"id":"wamid.out"}]}
                        """, MediaType.APPLICATION_JSON));
        WhatsAppSessionClient client = new WhatsAppSessionClient(properties("token"), builder, new ObjectMapper());

        assertThat(client.sendText("919800000111", "Reply Yes to post it.").providerId()).isEqualTo("wamid.out");
        server.verify();
    }

    @Test
    void aMissingTokenDoesNotCallWhatsApp() {
        RestClient.Builder builder = RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        WhatsAppSessionClient client = new WhatsAppSessionClient(properties(""), builder, new ObjectMapper());

        assertThat(client.sendText("919800000111", "hello").status()).isEqualTo("blocked");
        server.verify();
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
                null
        );
    }
}
