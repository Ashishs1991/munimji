package in.munimsaathi.inventory;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Product;
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
class OpeningStockTest {

    private static final Instant COUNTED_AT = Instant.parse("2026-01-15T04:30:00Z");

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
    private CountService counts;

    @Autowired
    private CountLineRepository countLines;

    @Autowired
    private StockMovementRepository movements;

    @Autowired
    private BatchRepository batches;

    @Test
    void confirmedCountPostsOnceAndReplayDoesNotAddAgain() {
        Shop shop = shop();
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        counts.propose(shop.userId(), sessionId, shop.productId(), new BigDecimal("5"), "box", null);
        assertThat(counts.progress(shop.userId(), sessionId)).isEqualTo(new CountProgress(0, 1));

        UUID operationId = UUID.randomUUID();
        counts.confirm(shop.userId(), sessionId, operationId);
        counts.confirm(shop.userId(), sessionId, operationId);

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(counts.progress(shop.userId(), sessionId)).isEqualTo(new CountProgress(1, 0));
        assertThat(movements.findByOperationId(operationId)).singleElement().satisfies(movement -> {
            assertThat(movement.getReason()).isEqualTo(StockReason.OPENING);
            assertThat(movement.getSignedBaseQuantity()).isEqualByComparingTo("50");
            assertThat(movement.getFactorToBase()).isEqualByComparingTo("10");
            assertThat(movement.getOccurredAt()).isEqualTo(COUNTED_AT);
            assertThat(movement.getBatchId()).isNull();
        });
    }

    @Test
    void recountAdjustsToTheNewQuantityInsteadOfAdding() {
        Shop shop = shop();
        UUID opening = UUID.randomUUID();
        confirmBoxes(shop, opening, new BigDecimal("5"));
        UUID recountSession = counts.start(shop.userId(), shop.godownId(), COUNTED_AT.plusSeconds(3600), "shelf A");
        counts.propose(shop.userId(), recountSession, shop.productId(), new BigDecimal("4"), "box", null);
        UUID recount = UUID.randomUUID();
        counts.confirm(shop.userId(), recountSession, recount);
        counts.confirm(shop.userId(), recountSession, opening);

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(movements.findByOperationId(recount)).singleElement()
                .satisfies(movement -> {
                    assertThat(movement.getReason()).isEqualTo(StockReason.ADJUSTMENT);
                    assertThat(movement.getSignedBaseQuantity()).isEqualByComparingTo("-10");
                });
    }

    @Test
    void unknownExpiryStaysUnknown() {
        Shop shop = shop();
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        counts.propose(shop.userId(), sessionId, shop.productId(), BigDecimal.ONE, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());

        CountLine line = countLines.findBySessionIdOrderByProductIdAsc(sessionId).get(0);
        assertThat(line.getExpiryPrecision()).isEqualTo(ExpiryPrecision.UNKNOWN);
        assertThat(line.getExpiryDate()).isNull();
        assertThat(line.getBatchId()).isNull();
        assertThat(batches.countByProductId(shop.productId())).isZero();
    }

    @Test
    void readableExpiryIsStoredAsThatDate() {
        Shop shop = shop();
        LocalDate expiry = LocalDate.of(2027, 3, 31);
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        counts.propose(shop.userId(), sessionId, shop.productId(), BigDecimal.ONE, "box", expiry);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());

        CountLine line = countLines.findBySessionIdOrderByProductIdAsc(sessionId).get(0);
        assertThat(line.getExpiryPrecision()).isEqualTo(ExpiryPrecision.DAY);
        assertThat(line.getExpiryDate()).isEqualTo(expiry);
        assertThat(batches.findByProductIdAndExpiryDate(shop.productId(), expiry)).isPresent();
    }

    @Test
    void confirmingAfterStockChangedRequiresAFreshCount() {
        Shop shop = shop();
        UUID first = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        UUID second = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        counts.propose(shop.userId(), first, shop.productId(), new BigDecimal("5"), "box", null);
        counts.propose(shop.userId(), second, shop.productId(), new BigDecimal("5"), "box", null);
        counts.confirm(shop.userId(), first, UUID.randomUUID());

        assertThatThrownBy(() -> counts.confirm(shop.userId(), second, UUID.randomUUID()))
                .isInstanceOf(StaleCountException.class);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
    }

    @Test
    void aRecountToZeroEmptiesTheShelf() {
        Shop shop = shop();
        confirmBoxes(shop, UUID.randomUUID(), new BigDecimal("5"));
        UUID recountSession = counts.start(shop.userId(), shop.godownId(), COUNTED_AT.plusSeconds(3600), "empty shelf");
        counts.propose(shop.userId(), recountSession, shop.productId(), BigDecimal.ZERO, "box", null);
        counts.confirm(shop.userId(), recountSession, UUID.randomUUID());

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("0");
    }

    private void confirmBoxes(Shop shop, UUID operationId, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), COUNTED_AT, "shelf A");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, operationId);
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Folder House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(userId, "FOLD-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false);
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        return new Shop(userId, godown.getId(), product.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID productId) {
    }
}
