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
    void temporaryContentFailureRetriesWithAFreshMediaLookup() {
        RestClient.Builder builder = RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v20.0/media-2"))
                .andRespond(withSuccess("{\"url\":\"https://lookaside.fbsbx.com/first\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://lookaside.fbsbx.com/first"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://graph.facebook.com/v20.0/media-2"))
                .andRespond(withSuccess("{\"url\":\"https://lookaside.fbsbx.com/second\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://lookaside.fbsbx.com/second"))
                .andRespond(withSuccess(new byte[]{4,5}, MediaType.parseMediaType("audio/ogg")));
        assertThat(new WhatsAppSessionClient(properties("token"), builder, new ObjectMapper()).download("media-2")).containsExactly(4,5);
        server.verify();
    }

    @Test
    void authorizationFailuresAreNotBlindlyRetried() {
        RestClient.Builder builder = RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://graph.facebook.com/v20.0/media-3"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":190,\"fbtrace_id\":\"test-trace\"}}"));
        assertThat(new WhatsAppSessionClient(properties("token"), builder, new ObjectMapper()).download("media-3")).isNull();
        server.verify();
    }

    @Test
    void signedMediaDownloadUrlKeepsItsExistingPercentEncoding() {
        RestClient.Builder builder = RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        String signed = "https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=123&hash=a%2Fb%3D";
        server.expect(requestTo("https://graph.facebook.com/v20.0/media-1"))
                .andRespond(withSuccess("{\"url\":\"" + signed + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(signed)).andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(new byte[]{1,2,3}, MediaType.parseMediaType("audio/ogg")));
        var client = new WhatsAppSessionClient(properties("token"), builder, new ObjectMapper());
        assertThat(client.download("media-1")).containsExactly(1,2,3);
        server.verify();
    }

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
