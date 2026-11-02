package in.munimsaathi.whatsapp;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class HinglishRepliesTest {
    @Test void preservesNamesAndRupeesAndDoesNotTranslateTwice() {
        String result = HinglishReplies.render("Record money received from Anisha Gajak: ₹5000.00 via UPI. Stock will be unchanged");
        assertThat(result).isEqualTo("Paise mile hain — Anisha Gajak: ₹5000.00 via UPI. Stock mein koi badlav nahi hoga");
        assertThat(HinglishReplies.render(result)).isEqualTo(result);
    }
    @Test void payableConfirmationDoesNotSayMoneyWasAlreadyPaid() {
        String result=HinglishReplies.render("Record a supplier obligation: you owe Anisha Gajak ₹5000.00");
        assertThat(result).isEqualTo("Naya dene wala bakaya: aapko dene hain — Anisha Gajak ₹5000.00");
        assertThat(result).doesNotContain("diye");
    }
}
