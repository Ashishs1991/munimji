package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaLanguageModelTest {

    @Test
    void aConfiguredClientSendsTheCommandAndReturnsTheModelJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://meta.example/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("muse-spark-1.3")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("अग्रवाल को एक बॉक्स")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("You cannot approve")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"sale\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));
        MetaLanguageModel model = new MetaLanguageModel(properties(), builder, new ObjectMapper());

        assertThat(model.complete("अग्रवाल को एक बॉक्स")).isEqualTo("{\"intent\":\"sale\"}");
        server.verify();
    }

    @Test
    void aPhotoIsSentAsAnImageAndAPdfIsSentAsItsText() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] photo = new byte[] {1, 2, 3, 4};
        String photoBase64 = java.util.Base64.getEncoder().encodeToString(photo);
        server.expect(requestTo("https://meta.example/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image_url")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(photoBase64)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sha256 photo")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"sale\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));
        org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
        pdf.addPage(page);
        org.apache.pdfbox.pdmodel.PDPageContentStream stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page);
        stream.beginText();
        stream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(50, 700);
        stream.showText("Office folder bill");
        stream.endText();
        stream.close();
        java.io.ByteArrayOutputStream pdfBytes = new java.io.ByteArrayOutputStream();
        pdf.save(pdfBytes);
        pdf.close();
        server.expect(requestTo("https://meta.example/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Office folder bill")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("image_url"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"receive\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));
        MetaLanguageModel model = new MetaLanguageModel(properties(), builder, new ObjectMapper());

        assertThat(model.completeDocument("sha256 photo", "image/jpeg", "bill.jpg", photo)).contains("sale");
        assertThat(model.completeDocument("sha256 pdf", "application/pdf", "bill.pdf", pdfBytes.toByteArray())).contains("receive");
        server.verify();
    }

    @Test
    void aMissingKeyDoesNotCallTheModel() {
        MetaLanguageModel model = new MetaLanguageModel(
                new MunimProperties("Asia/Kolkata", "INR", new MunimProperties.Meta("", "", "muse-spark-1.3", "muse-voice-transcribe-1.0"), null, null, null, null, null),
                RestClient.builder(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> model.complete("sell one box"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MunimProperties properties() {
        return new MunimProperties(
                "Asia/Kolkata",
                "INR",
                new MunimProperties.Meta("test-key", "https://meta.example", "muse-spark-1.3", "muse-voice-transcribe-1.0"),
                null,
                null,
                null,
                null,
                null
        );
    }
}
