package in.munimsaathi.ledger;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.inventory.InsufficientStockException;
import in.munimsaathi.inventory.StockMovementRepository;
import in.munimsaathi.inventory.StockReason;
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
class OfficeFolderFixtureTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);
    private static final Instant AT = Instant.parse("2026-10-31T04:30:00Z");

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
    private PartyLedgerRepository ledger;

    @Autowired
    private AllocationRepository allocations;

    @Autowired
    private StockMovementRepository movements;

    @Test
    void officeFolderPostsStockAndPaiseOnce() {
        Shop shop = shop();
        UUID opening = UUID.randomUUID();
        UUID purchase = UUID.randomUUID();
        UUID saleOp = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID returned = UUID.randomUUID();

        openBoxes(shop, opening, new BigDecimal("5"));
        assertBooks(shop, "50", 0L);

        UUID purchaseId = posting.receiveGoods(
                shop.userId(), purchase, shop.supplierId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", DAY, AT
        );
        assertBooks(shop, "70", 0L);
        assertThat(documents.findById(purchaseId).orElseThrow().getTotalPaise()).isNull();
        assertThat(ledger.countByPartyId(shop.supplierId())).isZero();
        assertThat(ledger.countByPartyId(shop.customerId())).isZero();

        UUID saleId = posting.sell(
                shop.userId(), saleOp, shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
        assertBooks(shop, "60", Paise.fromRupees(1000));
        assertThat(movements.findByOperationId(saleOp)).singleElement()
                .satisfies(movement -> {
                    assertThat(movement.getReason()).isEqualTo(StockReason.SALE);
                    assertThat(movement.getSignedBaseQuantity()).isEqualByComparingTo("-10");
                });

        posting.receivePayment(shop.userId(), payment, shop.customerId(), saleId, Paise.fromRupees(400), "cash", DAY, AT);
        assertBooks(shop, "60", Paise.fromRupees(600));
        assertThat(movements.findByOperationId(payment)).isEmpty();
        assertThat(allocations.allocatedTo(saleId)).isEqualTo(Paise.fromRupees(400));

        posting.acceptReturn(
                shop.userId(), returned, shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), true, "resellable return", DAY, AT
        );
        assertBooks(shop, "62", Paise.fromRupees(400));
        assertThat(movements.findByOperationId(returned)).singleElement()
                .satisfies(movement -> {
                    assertThat(movement.getReason()).isEqualTo(StockReason.RETURN);
                    assertThat(movement.getSignedBaseQuantity()).isEqualByComparingTo("2");
                });
        long ledgerRows = ledger.countByPartyId(shop.customerId());
        long allocationTotal = allocations.allocatedTo(saleId);

        openBoxes(shop, opening, new BigDecimal("5"));
        posting.receiveGoods(
                shop.userId(), purchase, shop.supplierId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", DAY, AT
        );
        posting.sell(
                shop.userId(), saleOp, shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
        posting.receivePayment(shop.userId(), payment, shop.customerId(), saleId, Paise.fromRupees(400), "cash", DAY, AT);
        posting.acceptReturn(
                shop.userId(), returned, shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), true, "resellable return", DAY, AT
        );

        assertBooks(shop, "62", Paise.fromRupees(400));
        assertThat(ledger.countByPartyId(shop.customerId())).isEqualTo(ledgerRows);
        assertThat(allocations.allocatedTo(saleId)).isEqualTo(allocationTotal);
    }

    @Test
    void saleRefusesToTakeStockBelowZero() {
        Shop shop = shop();
        openBoxes(shop, UUID.randomUUID(), new BigDecimal("5"));

        assertThatThrownBy(() -> posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                new BigDecimal("6"), "box", Paise.fromRupees(1000), DAY, AT
        )).isInstanceOf(InsufficientStockException.class);

        assertBooks(shop, "50", 0L);
    }

    @Test
    void damagedReturnDoesNotRestock() {
        Shop shop = shop();
        openBoxes(shop, UUID.randomUUID(), new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );

        posting.acceptReturn(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, shop.productId(), shop.godownId(),
                new BigDecimal("2"), "piece", Paise.fromRupees(200), false, "damaged", DAY, AT
        );

        assertBooks(shop, "40", Paise.fromRupees(800));
    }

    @Test
    void paymentLargerThanTheSaleIsRejected() {
        Shop shop = shop();
        openBoxes(shop, UUID.randomUUID(), new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );

        assertThatThrownBy(() -> posting.receivePayment(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(1001), "cash", DAY, AT
        )).isInstanceOf(IllegalArgumentException.class);

        assertBooks(shop, "40", Paise.fromRupees(1000));
    }

    private void assertBooks(Shop shop, String pieces, long receivablePaise) {
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo(pieces);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(receivablePaise);
    }

    private void openBoxes(Shop shop, UUID operationId, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "office folders");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, operationId);
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Folder House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "FOLD-" + userId.toString().substring(0, 8),
                "Office folder",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party supplier = parties.create(userId, "Paper Mill", PartyRole.SUPPLIER);
        Party customer = parties.create(userId, "City Stores", PartyRole.CUSTOMER);
        return new Shop(userId, godown.getId(), product.getId(), supplier.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID productId, UUID supplierId, UUID customerId) {
    }
}
