package in.munimsaathi.eway;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EwayClientTest {

    @Test
    void aMissingCredentialDoesNotCallThePortal() {
        RestClient.Builder builder = RestClient.builder();
        MockServer server = new MockServer(builder);
        EwayClient client = new EwayClient(properties(true, ""), builder, new ObjectMapper());

        assertThatThrownBy(() -> client.generate("{\"docNo\":\"1\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("E-way submission is not configured.");
        server.verify();
    }

    @Test
    void generateSendsBase64AndReadsTheOfficialNumberOnlyFromAnAcceptedResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockServer server = new MockServer(builder);
        String payload = "{\"docNo\":\"1\",\"totInvValue\":1000.00}";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String result = Base64.getEncoder().encodeToString("""
                {"ewayBillNo":123456789,"ewayBillDate":"01/08/2026 10:00:00 AM","validUpto":"02/08/2026 11:59:00 PM"}
                """.getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/auth"))
                .andExpect(header("client-id", "client"))
                .andExpect(header("client-secret", "secret"))
                .andExpect(header("gstin", "29ABCDE8755F1Z2"))
                .andExpect(content().string(containsString("ACCESSTOKEN")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("portal-secret"))))
                .andRespond(withSuccess("""
                        {"status":"1","authtoken":"token-1","sek":"sek-1"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/ewayapi"))
                .andExpect(header("authtoken", "token-1"))
                .andExpect(content().string(containsString("GENEWAYBILL")))
                .andExpect(content().string(containsString(encoded)))
                .andRespond(withSuccess("""
                        {"status":"1","data":"%s"}
                        """.formatted(result), MediaType.APPLICATION_JSON));
        EwayClient client = new EwayClient(properties(true, "portal-secret"), builder, new ObjectMapper());

        EwayReply reply = client.generate(payload);

        assertThat(reply.status()).isEqualTo("1");
        assertThat(reply.ewayBillNo()).isEqualTo("123456789");
        assertThat(reply.ewayBillDate()).isEqualTo("01/08/2026 10:00:00 AM");
        assertThat(reply.validUpto()).isEqualTo("02/08/2026 11:59:00 PM");
        assertThat(reply.billRequestSent()).isTrue();
        server.verify();
    }

    @Test
    void aPortalRejectionDoesNotInventABillNumber() {
        RestClient.Builder builder = RestClient.builder();
        MockServer server = new MockServer(builder);
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/auth"))
                .andRespond(withSuccess("""
                        {"status":"1","authtoken":"token-1","sek":"sek-1"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/ewayapi"))
                .andRespond(withSuccess("""
                        {"status":"0","error":{"errorCodes":"217"}}
                        """, MediaType.APPLICATION_JSON));
        EwayClient client = new EwayClient(properties(true, "portal-secret"), builder, new ObjectMapper());

        EwayReply reply = client.generate("{\"docNo\":\"1\"}");

        assertThat(reply.status()).isEqualTo("0");
        assertThat(reply.error()).isEqualTo("217");
        assertThat(reply.ewayBillNo()).isNull();
        assertThat(reply.billRequestSent()).isTrue();
        server.verify();
    }

    @Test
    void aLostResponseIsUnknownAndDoesNotCarryABillNumber() {
        RestClient.Builder builder = RestClient.builder();
        MockServer server = new MockServer(builder);
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/auth"))
                .andRespond(withSuccess("""
                        {"status":"1","authtoken":"token-1","sek":"sek-1"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/ewayapi"))
                .andRespond(withServerError());
        EwayClient client = new EwayClient(properties(true, "portal-secret"), builder, new ObjectMapper());

        EwayReply reply = client.generate("{\"docNo\":\"1\"}");

        assertThat(reply.status()).isNull();
        assertThat(reply.ewayBillNo()).isNull();
        assertThat(reply.billRequestSent()).isTrue();
        server.verify();
    }

    @Test
    void lookupAsksForTheExistingBillAndDoesNotGenerateAnother() {
        RestClient.Builder builder = RestClient.builder();
        MockServer server = new MockServer(builder);
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/auth"))
                .andRespond(withSuccess("""
                        {"status":"1","authtoken":"token-1","sek":"sek-1"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://eway.example/ewaybillapi/v1.03/ewayapi"))
                .andExpect(content().string(containsString("\"action\":\"GETEWAYBILL\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("GENEWAYBILL"))))
                .andRespond(withSuccess("""
                        {"status":"0","error":{"errorCodes":"not found"}}
                        """, MediaType.APPLICATION_JSON));
        EwayClient client = new EwayClient(properties(true, "portal-secret"), builder, new ObjectMapper());

        EwayReply reply = client.lookup("INV", "1");

        assertThat(reply.ewayBillNo()).isNull();
        assertThat(reply.billRequestSent()).isFalse();
        server.verify();
    }

    private static MunimProperties properties(boolean enabled, String password) {
        return new MunimProperties(
                "Asia/Kolkata",
                "INR",
                null,
                null,
                null,
                null,
                new MunimProperties.Eway(
                        enabled,
                        "sandbox",
                        "https://eway.example",
                        "client",
                        "secret",
                        "29ABCDE8755F1Z2",
                        "eway-user",
                        password
                ),
                null
        );
    }

    private static final class MockServer {
        private final org.springframework.test.web.client.MockRestServiceServer server;

        private MockServer(RestClient.Builder builder) {
            this.server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        }

        private org.springframework.test.web.client.ResponseActions expect(
                org.springframework.test.web.client.RequestMatcher matcher
        ) {
            return server.expect(matcher);
        }

        private void verify() {
            server.verify();
        }
    }
}
