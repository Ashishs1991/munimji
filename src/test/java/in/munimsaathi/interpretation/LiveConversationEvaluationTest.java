package in.munimsaathi.interpretation;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: real Meta inference, synthetic businesses, disposable PostgreSQL; no WhatsApp sends. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_META_LIVE_EVAL", matches = "true")
class LiveConversationEvaluationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
        registry.add("munim.meta.api-key", () -> System.getenv("META_EVAL_KEY"));
        registry.add("munim.meta.base-url", () -> "https://api.meta.ai/v1");
        registry.add("munim.meta.reasoning-model", () -> "muse-spark-1.3");
        registry.add("munim.whatsapp.auto-reply", () -> "false");
    }

    @Autowired CommandInterpreter interpreter;
    @Autowired DraftBook drafts;
    @Autowired BusinessRepository businesses;
    @Autowired MembershipRepository memberships;
    @Autowired GodownRepository godowns;
    @Autowired TenantContext tenants;
    @Autowired PartyBook parties;
    @Autowired PostingService posting;
    @Autowired CountService counts;
    @Autowired CatalogService catalog;

    @Test
    void realModelRoutesHindiLoanLocationAndPurchaseConversations() {
        UUID user = UUID.randomUUID();
        var business = businesses.save(new Business(UUID.randomUUID(), "Synthetic evaluation shop", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), user, MembershipRole.OWNER));
        tenants.selectBusiness(user, business.getId());
        var godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        LocalDate day = LocalDate.now();
        Instant at = Instant.now();

        var loan = interpreter.interpret(user, "अनिल भाई को दस हजार रुपये उधार दिए हैं, कैश में।", day, at);
        assertThat(drafts.inspect(user, loan.draft().id()).getKind()).isEqualTo("OUTGOING_PAYMENT");
        var party = interpreter.interpret(user, "हाँ", day, at);
        assertThat(party.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        var loanRow = drafts.inspect(user, party.draft().id());
        assertThat(loanRow.getAmountPaise()).isEqualTo(1000000L);
        drafts.approve(user, party.draft().id(), party.draft().version(), party.draft().contentHash());
        assertThat(posting.customerReceivablePaise(user, loanRow.getCustomerId())).isEqualTo(1000000L);

        var note = interpreter.interpret(user, "अनिल भाई का माल गोदाम वन में रखा है।", day, at);
        assertThat(drafts.inspect(user, note.draft().id()).getKind()).isEqualTo("LOCATION");
        drafts.approve(user, note.draft().id(), note.draft().version(), note.draft().contentHash());
        var query = interpreter.interpret(user, "अनिल भाई का माल कहाँ रखा है?", day, at);
        assertThat(query.draft()).isNull();
        assertThat(query.answer()).contains("Godown 1");

        var purchase = interpreter.interpret(user, "गुप्ता ट्रेडर्स से पाँच बोरी प्लास्टिक बॉक्स खरीदे हैं। गोदाम वन में रखे हैं। कीमत अभी नहीं पता।", day, at);
        assertThat(drafts.inspect(user, purchase.draft().id()).getKind()).isEqualTo("RECEIPT");
        var supplier = interpreter.interpret(user, "हाँ", day, at);
        assertThat(supplier.draft().id()).isEqualTo(purchase.draft().id());
        var product = interpreter.interpret(user, "हाँ बना दो", day, at);
        assertThat(product.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        var row = drafts.inspect(user, product.draft().id());
        assertThat(row.getDisplayQuantity()).isEqualByComparingTo("5");
        assertThat(row.getLocationId()).isEqualTo(godown.getId());
        assertThat(counts.balance(user, row.getProductId(), godown.getId())).isEqualByComparingTo("0");
        drafts.approve(user, row.getId(), product.draft().version(), product.draft().contentHash());
        assertThat(counts.balance(user, row.getProductId(), godown.getId())).isEqualByComparingTo("5");
        assertThat(posting.supplierPayablePaise(user, row.getCustomerId())).isZero();
    }
}
