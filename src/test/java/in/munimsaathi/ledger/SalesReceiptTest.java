package in.munimsaathi.ledger;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.inventory.InsufficientStockException;
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
class SalesReceiptTest {

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
    private InvoiceNumberRepository invoiceNumbers;

    @Autowired
    private AllocationRepository allocations;

    @Test
    void salePostsOnceAndACanceledNumberIsNotReused() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleOperation = UUID.randomUUID();
        UUID saleId = sell(shop, saleOperation, shop.customerId());
        sell(shop, saleOperation, shop.customerId());

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));
        assertThat(invoiceNumbers.countByBusinessId(shop.businessId())).isEqualTo(1);
        assertThat(invoiceNumbers.findByDocumentId(saleId).orElseThrow().getNumber()).isEqualTo(1);

        UUID cancel = UUID.randomUUID();
        posting.cancelInvoice(shop.userId(), cancel, saleId);
        posting.cancelInvoice(shop.userId(), cancel, saleId);

        assertThat(invoiceNumbers.findByBusinessIdAndNumber(shop.businessId(), 1).orElseThrow().getStatus())
                .isEqualTo(InvoiceNumberStatus.CANCELED);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));

        UUID secondSale = sell(shop, UUID.randomUUID(), shop.customerId());
        assertThat(invoiceNumbers.findByDocumentId(secondSale).orElseThrow().getNumber()).isEqualTo(2);
        assertThat(invoiceNumbers.findByBusinessIdAndNumber(shop.businessId(), 1).orElseThrow().getStatus())
                .isEqualTo(InvoiceNumberStatus.CANCELED);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("30");
    }

    @Test
    void unallocatedReceiptStaysUnapplied() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = sell(shop, UUID.randomUUID(), shop.customerId());

        UUID loose = UUID.randomUUID();
        UUID receiptId = posting.receiveUnallocated(
                shop.userId(), loose, shop.customerId(), Paise.fromRupees(400), "cash", DAY, AT
        );
        posting.receiveUnallocated(shop.userId(), loose, shop.customerId(), Paise.fromRupees(400), "cash", DAY, AT);

        assertThat(documents.findById(receiptId).orElseThrow().getPaymentMethod()).isEqualTo("cash");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(600));
        assertThat(allocations.allocatedTo(saleId)).isZero();
        assertThat(posting.unappliedReceiptPaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(400));
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");

        posting.receivePayment(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(200), "upi", DAY, AT
        );
        assertThat(allocations.allocatedTo(saleId)).isEqualTo(Paise.fromRupees(200));
        assertThat(posting.unappliedReceiptPaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(400));
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(400));
    }

    @Test
    void saleRefusesToGoNegativeAndIssuesNoInvoice() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));

        assertThatThrownBy(() -> posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                new BigDecimal("6"), "box", Paise.fromRupees(1000), DAY, AT
        )).isInstanceOf(InsufficientStockException.class);

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(invoiceNumbers.countByBusinessId(shop.businessId())).isZero();
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isZero();
    }

    @Test
    void theSameCustomerNameDoesNotShareABalance() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        Party other = parties.create(shop.userId(), "Agarwal", PartyRole.CUSTOMER);
        sell(shop, UUID.randomUUID(), shop.customerId());

        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));
        assertThat(posting.customerReceivablePaise(shop.userId(), other.getId())).isZero();
    }

    @Test
    void eachBusinessNumbersItsOwnInvoicesFromOne() {
        Shop first = shop();
        Shop second = shop();
        openBoxes(first, BigDecimal.ONE);
        openBoxes(second, BigDecimal.ONE);

        UUID firstSale = sell(first, UUID.randomUUID(), first.customerId());
        UUID secondSale = sell(second, UUID.randomUUID(), second.customerId());

        assertThat(invoiceNumbers.findByDocumentId(firstSale).orElseThrow().getNumber()).isEqualTo(1);
        assertThat(invoiceNumbers.findByDocumentId(secondSale).orElseThrow().getNumber()).isEqualTo(1);
    }

    @Test
    void twoPaymentsCannotOverpayTheSameSale() throws Exception {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = sell(shop, UUID.randomUUID(), shop.customerId());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        for (int attempt = 0; attempt < 2; attempt++) {
            pool.submit(() -> {
                try {
                    start.await();
                    posting.receivePayment(
                            shop.userId(), UUID.randomUUID(), shop.customerId(), saleId,
                            Paise.fromRupees(700), "cash", DAY, AT
                    );
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException exception) {
                    if (exception.getMessage().contains("still owed")) {
                        rejected.incrementAndGet();
                    } else {
                        throw exception;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(allocations.allocatedTo(saleId)).isEqualTo(Paise.fromRupees(700));
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(300));
    }

    private UUID sell(Shop shop, UUID operationId, UUID customerId) {
        return posting.sell(
                shop.userId(),
                operationId,
                customerId,
                shop.productId(),
                shop.godownId(),
                BigDecimal.ONE,
                "box",
                Paise.fromRupees(1000),
                DAY,
                AT
        );
    }

    private void openBoxes(Shop shop, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "sales");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "SALE-" + userId.toString().substring(0, 8),
                "Office folder",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
}
