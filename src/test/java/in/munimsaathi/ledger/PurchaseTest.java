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
class PurchaseTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 30);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 4, 30);
    private static final Instant AT = Instant.parse("2026-04-30T04:30:00Z");

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
    private PartyLedgerRepository ledger;

    @Autowired
    private BillReviewRepository reviews;

    @Autowired
    private StockMovementRepository movements;

    @Test
    void billQuantityStaysSeparateFromTheGoodsReceived() {
        Shop shop = shop();
        UUID receiptOperation = UUID.randomUUID();
        UUID receiptId = posting.receiveGoods(
                shop.userId(), receiptOperation, shop.supplierId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", DAY, AT
        );

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");
        PostedDocument receipt = documents.findById(receiptId).orElseThrow();
        assertThat(receipt.getTotalPaise()).isNull();
        assertThat(receipt.getFinanceStatus()).isEqualTo(FinanceStatus.PENDING);
        assertThat(ledger.countByPartyId(shop.supplierId())).isZero();
        assertThat(lines.findByDocumentId(receiptId).get(0).getBaseQuantity()).isEqualByComparingTo("20");

        UUID firstBill = UUID.randomUUID();
        PurchaseBillOutcome first = bill(shop, firstBill, receiptId, "SM-1", new BigDecimal("15"), "piece", Paise.fromRupees(150));
        assertThat(first.needsReview()).isFalse();
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");
        assertThat(documents.findById(receiptId).orElseThrow().getFinanceStatus()).isEqualTo(FinanceStatus.PARTIAL);
        assertThat(lines.findByDocumentId(first.documentId()).get(0).getBaseQuantity()).isEqualByComparingTo("15");
        assertThat(documents.findById(first.documentId()).orElseThrow().getBillNumber()).isEqualTo("sm-1");
        assertThat(movements.findByOperationId(firstBill)).isEmpty();
        assertThat(ledger.countByPartyId(shop.supplierId())).isZero();

        UUID secondBill = UUID.randomUUID();
        PurchaseBillOutcome second = bill(shop, secondBill, receiptId, "SM-2", new BigDecimal("5"), "piece", Paise.fromRupees(50));
        assertThat(second.needsReview()).isFalse();
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");
        assertThat(documents.findById(receiptId).orElseThrow().getFinanceStatus()).isEqualTo(FinanceStatus.BILLED);
        assertThat(movements.findByOperationId(receiptOperation)).hasSize(1);

        PurchaseBillOutcome replay = bill(shop, secondBill, receiptId, "SM-2", new BigDecimal("5"), "piece", Paise.fromRupees(50));
        assertThat(replay.documentId()).isEqualTo(second.documentId());
        assertThat(replay.needsReview()).isFalse();
        assertThat(documents.countByPartyIdAndKind(shop.supplierId(), DocumentKind.SUPPLIER_BILL)).isEqualTo(2);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");

        PurchaseBillOutcome sameNumber = bill(shop, UUID.randomUUID(), receiptId, "SM-1", BigDecimal.ONE, "piece", Paise.fromRupees(10));
        assertThat(sameNumber.needsReview()).isTrue();
        assertThat(sameNumber.documentId()).isNull();
        assertThat(reviews.findById(sameNumber.reviewId()).orElseThrow().getStatus()).isEqualTo("NEEDS_REVIEW");

        PurchaseBillOutcome sameContent = bill(shop, UUID.randomUUID(), receiptId, "SM-9", new BigDecimal("15"), "piece", Paise.fromRupees(150));
        assertThat(sameContent.needsReview()).isTrue();
        assertThat(documents.countByPartyIdAndKind(shop.supplierId(), DocumentKind.SUPPLIER_BILL)).isEqualTo(2);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");
    }

    @Test
    void billLargerThanTheReceiptIsRejected() {
        Shop shop = shop();
        UUID receiptId = posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.supplierId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", DAY, AT
        );

        assertThatThrownBy(() -> bill(shop, UUID.randomUUID(), receiptId, "SM-3", new BigDecimal("3"), "box", Paise.fromRupees(300)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("20");
        assertThat(documents.countByPartyIdAndKind(shop.supplierId(), DocumentKind.SUPPLIER_BILL)).isZero();
        assertThat(documents.findById(receiptId).orElseThrow().getFinanceStatus()).isEqualTo(FinanceStatus.PENDING);
    }

    @Test
    void theSameBillNumberInAnotherBusinessIsADifferentBill() {
        Shop first = shop();
        Shop second = shop();
        UUID firstReceipt = posting.receiveGoods(
                first.userId(), UUID.randomUUID(), first.supplierId(), first.productId(), first.godownId(),
                BigDecimal.ONE, "box", DAY, AT
        );
        UUID secondReceipt = posting.receiveGoods(
                second.userId(), UUID.randomUUID(), second.supplierId(), second.productId(), second.godownId(),
                BigDecimal.ONE, "box", DAY, AT
        );

        PurchaseBillOutcome firstBill = bill(first, UUID.randomUUID(), firstReceipt, "SM-1", BigDecimal.ONE, "box", Paise.fromRupees(100));
        PurchaseBillOutcome secondBill = bill(second, UUID.randomUUID(), secondReceipt, "SM-1", BigDecimal.ONE, "box", Paise.fromRupees(100));

        assertThat(firstBill.needsReview()).isFalse();
        assertThat(secondBill.needsReview()).isFalse();
        assertThat(secondBill.documentId()).isNotEqualTo(firstBill.documentId());
    }

    private PurchaseBillOutcome bill(
            Shop shop,
            UUID operationId,
            UUID receiptId,
            String billNumber,
            BigDecimal quantity,
            String unit,
            long amountPaise
    ) {
        return posting.recordSupplierBill(
                shop.userId(),
                operationId,
                receiptId,
                billNumber,
                PERIOD_START,
                PERIOD_END,
                shop.productId(),
                quantity,
                unit,
                amountPaise,
                DAY,
                AT
        );
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Paper House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "BAG-" + userId.toString().substring(0, 8),
                "Carry bag",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party supplier = parties.create(userId, "Mill " + userId.toString().substring(0, 4), PartyRole.SUPPLIER);
        return new Shop(userId, godown.getId(), product.getId(), supplier.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID productId, UUID supplierId) {
    }
}
