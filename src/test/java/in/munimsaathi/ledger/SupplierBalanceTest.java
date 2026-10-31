package in.munimsaathi.ledger;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
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
class SupplierBalanceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 31);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);
    private static final Instant AT = Instant.parse("2026-05-31T04:30:00Z");

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
    private AllocationRepository allocations;

    @Test
    void goodsReceiptDoesNotInventAPriceAndTheApprovedBillDoes() {
        Shop shop = shop(PartyRole.SUPPLIER);
        UUID receiptId = receiveBox(shop);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("10");

        assertThatThrownBy(() -> posting.paySupplier(
                shop.userId(), UUID.randomUUID(), shop.partyId(), null, Paise.fromRupees(100), "cash", DAY, AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posting.approveSupplierBill(shop.userId(), UUID.randomUUID(), receiptId, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();

        UUID billId = bill(shop, receiptId, "MILL-1", Paise.fromRupees(500));
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();

        UUID approval = UUID.randomUUID();
        posting.approveSupplierBill(shop.userId(), approval, billId, AT);
        posting.approveSupplierBill(shop.userId(), approval, billId, AT);

        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(500));
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("10");
    }

    @Test
    void supplierPaymentCanBePartial() {
        Shop shop = shop(PartyRole.SUPPLIER);
        UUID billId = bill(shop, receiveBox(shop), "MILL-2", Paise.fromRupees(1000));
        posting.approveSupplierBill(shop.userId(), UUID.randomUUID(), billId, AT);

        UUID first = UUID.randomUUID();
        posting.paySupplier(shop.userId(), first, shop.partyId(), billId, Paise.fromRupees(300), "upi", DAY, AT);
        posting.paySupplier(shop.userId(), first, shop.partyId(), billId, Paise.fromRupees(300), "upi", DAY, AT);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(700));
        assertThat(allocations.allocatedTo(billId)).isEqualTo(Paise.fromRupees(300));

        posting.paySupplier(shop.userId(), UUID.randomUUID(), shop.partyId(), billId, Paise.fromRupees(700), "upi", DAY, AT);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();

        assertThatThrownBy(() -> posting.paySupplier(
                shop.userId(), UUID.randomUUID(), shop.partyId(), billId, Paise.fromRupees(1), "upi", DAY, AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();
    }

    @Test
    void unallocatedSupplierPaymentStaysOffTheBill() {
        Shop shop = shop(PartyRole.SUPPLIER);
        UUID billId = bill(shop, receiveBox(shop), "MILL-3", Paise.fromRupees(1000));
        posting.approveSupplierBill(shop.userId(), UUID.randomUUID(), billId, AT);

        UUID loose = UUID.randomUUID();
        posting.paySupplier(shop.userId(), loose, shop.partyId(), null, Paise.fromRupees(200), "cash", DAY, AT);
        posting.paySupplier(shop.userId(), loose, shop.partyId(), null, Paise.fromRupees(200), "cash", DAY, AT);

        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(800));
        assertThat(allocations.allocatedTo(billId)).isZero();
        assertThat(posting.unappliedSupplierPaymentPaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(200));

        posting.paySupplier(shop.userId(), UUID.randomUUID(), shop.partyId(), billId, Paise.fromRupees(800), "cash", DAY, AT);
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isZero();
        assertThat(allocations.allocatedTo(billId)).isEqualTo(Paise.fromRupees(800));
        assertThat(posting.unappliedSupplierPaymentPaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(200));
    }

    @Test
    void receivableAndPayableAreNotNetted() {
        Shop shop = shop(PartyRole.BOTH);
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.partyId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
        UUID billId = bill(shop, receiveBox(shop), "MILL-4", Paise.fromRupees(400));
        posting.approveSupplierBill(shop.userId(), UUID.randomUUID(), billId, AT);

        assertThat(posting.customerReceivablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(1000));
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(400));

        posting.paySupplier(shop.userId(), UUID.randomUUID(), shop.partyId(), billId, Paise.fromRupees(100), "cash", DAY, AT);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(1000));
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(300));

        posting.receivePayment(shop.userId(), UUID.randomUUID(), shop.partyId(), saleId, Paise.fromRupees(200), "upi", DAY, AT);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(800));
        assertThat(posting.supplierPayablePaise(shop.userId(), shop.partyId())).isEqualTo(Paise.fromRupees(300));
    }

    private UUID receiveBox(Shop shop) {
        return posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.partyId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", DAY, AT
        );
    }

    private UUID bill(Shop shop, UUID receiptId, String number, long amountPaise) {
        PurchaseBillOutcome outcome = posting.recordSupplierBill(
                shop.userId(),
                UUID.randomUUID(),
                receiptId,
                number,
                PERIOD_START,
                PERIOD_END,
                shop.productId(),
                BigDecimal.ONE,
                "box",
                amountPaise,
                DAY,
                AT
        );
        assertThat(outcome.needsReview()).isFalse();
        return outcome.documentId();
    }

    private void openBoxes(Shop shop, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "supplier");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop(PartyRole role) {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Both Books", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "SUP-" + userId.toString().substring(0, 8),
                "Carry bag",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party party = parties.create(userId, "Agarwal " + userId.toString().substring(0, 4), role);
        return new Shop(userId, godown.getId(), product.getId(), party.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID productId, UUID partyId) {
    }
}
