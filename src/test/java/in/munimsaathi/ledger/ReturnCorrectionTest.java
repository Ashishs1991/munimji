package in.munimsaathi.ledger;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.inventory.StockMovementRepository;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReturnCorrectionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);
    private static final Instant AT = Instant.parse("2026-06-15T04:30:00Z");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private GodownRepository godowns;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Autowired
    private CountService counts;

    @Autowired
    private PostingService posting;

    @Autowired
    private PostedDocumentRepository documents;

    @Autowired
    private DocumentLineRepository lines;

    @Autowired
    private CorrectionRepository corrections;

    @Autowired
    private StockMovementRepository movements;

    @Test
    void creditIsNotACashRefundAndTheSaleStaysAsPosted() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
        posting.receivePayment(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(1000), "cash", DAY, AT
        );
        long saleTotal = documents.findById(saleId).orElseThrow().getTotalPaise();
        BigDecimal soldPieces = lines.findByDocumentId(saleId).get(0).getBaseQuantity();

        UUID returnOperation = UUID.randomUUID();
        UUID creditId = posting.acceptReturn(
                shop.userId(), returnOperation, shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), false, "damaged on delivery", DAY, AT
        );
        posting.acceptReturn(
                shop.userId(), returnOperation, shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), false, "damaged on delivery", DAY, AT
        );

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(-200));
        assertThat(documents.findById(saleId).orElseThrow().getTotalPaise()).isEqualTo(saleTotal);
        assertThat(lines.findByDocumentId(saleId).get(0).getBaseQuantity()).isEqualByComparingTo(soldPieces);
        assertThat(documents.findById(creditId).orElseThrow().getTotalPaise()).isEqualTo(Paise.fromRupees(200));
        Correction correction = corrections.findByOperationId(returnOperation).orElseThrow();
        assertThat(correction.getReason()).isEqualTo("damaged on delivery");
        assertThat(correction.getApproverId()).isEqualTo(shop.userId());
        assertThat(correction.getSourceDocumentId()).isEqualTo(saleId);
        assertThat(correction.getReplacementDocumentId()).isEqualTo(creditId);
        assertThat(movements.findByOperationId(returnOperation)).isEmpty();

        UUID refundOperation = UUID.randomUUID();
        UUID refundId = posting.refundCash(
                shop.userId(), refundOperation, shop.customerId(), creditId, Paise.fromRupees(200), "paid back in cash", DAY, AT
        );
        posting.refundCash(
                shop.userId(), refundOperation, shop.customerId(), creditId, Paise.fromRupees(200), "paid back in cash", DAY, AT
        );

        assertThat(refundId).isNotEqualTo(creditId);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isZero();
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(documents.findById(creditId).orElseThrow().getTotalPaise()).isEqualTo(Paise.fromRupees(200));
        assertThat(documents.findById(saleId).orElseThrow().getTotalPaise()).isEqualTo(saleTotal);
        assertThat(corrections.findByOperationId(refundOperation).orElseThrow().getApproverId()).isEqualTo(shop.userId());
        assertThat(movements.findByOperationId(refundOperation)).isEmpty();

        assertThatThrownBy(() -> posting.refundCash(
                shop.userId(), UUID.randomUUID(), shop.customerId(), creditId, Paise.fromRupees(1), "again", DAY, AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restockHappensOnlyWhenApproved() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );

        posting.acceptReturn(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), true, "good pieces came back", DAY, AT
        );

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("42");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(800));
    }

    private void openBoxes(Shop shop, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "returns");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Return House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "RET-" + userId.toString().substring(0, 8),
                "Office folder",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "City Stores", PartyRole.CUSTOMER);
        return new Shop(userId, godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID productId, UUID customerId) {
    }
}
