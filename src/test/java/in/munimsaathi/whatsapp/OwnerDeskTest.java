package in.munimsaathi.whatsapp;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OwnerDeskTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
        registry.add("munim.whatsapp.owner-phone", () -> "+91 98000 00222");
        registry.add("munim.whatsapp.owner-business", () -> "Railway Stores");
        registry.add("munim.whatsapp.auto-reply", () -> "false");
    }

    @Autowired
    private WhatsAppSenderRepository senders;

    @Autowired
    private ProductRepository products;

    @Test
    void theOwnerPhoneOpensOneBusinessWithNoStock() {
        WhatsAppSender sender = senders.findByPhone("919800000222").orElseThrow();
        assertThat(products.search(sender.getBusinessId(), "Office folder")).hasSize(1);
        assertThat(senders.findByPhone("919800000222")).isPresent();
    }
}
