package in.munimsaathi.draft;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.FinanceStatus;
import in.munimsaathi.ledger.InvoiceNumberRepository;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostedDocument;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.ledger.PostingService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DraftApprovalTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final Instant AT = Instant.parse("2026-08-01T04:30:00Z");

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
    private DraftBook drafts;

    @Autowired
    private DraftLineRepository draftLines;

    @Autowired
    private PostingService posting;

    @Autowired
    private PostedDocumentRepository documents;

    @Autowired
    private InvoiceNumberRepository invoiceNumbers;

    @Test
    void nothingPostsUntilTheCurrentProposalIsApproved() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal proposed = sale(shop, BigDecimal.ONE, Paise.fromRupees(1000));

        assertThat(proposed.status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);
        assertThat(proposed.missingFields()).isEmpty();
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(documents.findByOperationId(proposed.id())).isEmpty();

        UUID posted = drafts.approve(shop.userId(), proposed.id(), proposed.version(), proposed.contentHash());
        drafts.approve(shop.userId(), proposed.id(), proposed.version(), proposed.contentHash());

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));
        assertThat(documents.findById(posted).orElseThrow().getTotalPaise()).isEqualTo(Paise.fromRupees(1000));
    }

    @Test
    void aMissingPriceIsNotInventedAndCanStillDispatchStock() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal proposed = sale(shop, BigDecimal.ONE, null);

        assertThat(proposed.missingFields()).containsExactly("price");
        assertThat(proposed.amountPaise()).isNull();
        assertThat(proposed.status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);

        UUID posted = drafts.approveCurrent(shop.userId());
        PostedDocument document = documents.findById(posted).orElseThrow();

        assertThat(document.getTotalPaise()).isNull();
        assertThat(document.getFinanceStatus()).isEqualTo(FinanceStatus.PENDING);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isZero();
        assertThat(invoiceNumbers.countByBusinessId(shop.businessId())).isZero();
    }

    @Test
    void aMissingQuantityStaysMissing() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal proposed = drafts.propose(
                shop.userId(), "sale", shop.customerId(), shop.productId(), shop.godownId(),
                null, "box", Paise.fromRupees(1000), DAY, AT
        );

        assertThat(proposed.missingFields()).contains("quantity");
        assertThat(proposed.status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
        assertThatThrownBy(() -> drafts.approve(shop.userId(), proposed.id(), proposed.version(), proposed.contentHash()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(documents.findByOperationId(proposed.id())).isEmpty();
    }

    @Test
    void aBlankProposalDoesNotInventTheActionPartyOrUnit() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal proposed = drafts.propose(
                shop.userId(), null, null, shop.productId(), shop.godownId(),
                BigDecimal.ONE, null, null, DAY, AT
        );

        assertThat(proposed.missingFields()).contains("action", "party", "unit", "price");
        assertThat(proposed.status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
        assertThatThrownBy(() -> drafts.approveCurrent(shop.userId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(documents.findByOperationId(proposed.id())).isEmpty();
    }

    @Test
    void yesApprovesOnlyOneCurrentProposal() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        sale(shop, BigDecimal.ONE, Paise.fromRupees(1000));
        sale(shop, BigDecimal.ONE, Paise.fromRupees(500));

        assertThatThrownBy(() -> drafts.approveCurrent(shop.userId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
    }

    @Test
    void anEditInvalidatesTheOldApproval() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal first = sale(shop, BigDecimal.ONE, Paise.fromRupees(1000));
        DraftProposal edited = drafts.edit(
                shop.userId(), first.id(), "sale", shop.customerId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", Paise.fromRupees(1000), DAY, AT
        );

        assertThat(edited.version()).isGreaterThan(first.version());
        assertThat(edited.contentHash()).isNotEqualTo(first.contentHash());
        assertThatThrownBy(() -> drafts.approve(shop.userId(), first.id(), first.version(), first.contentHash()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");

        drafts.approve(shop.userId(), edited.id(), edited.version(), edited.contentHash());
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("30");
    }

    @Test
    void anEditChangesTheLineThatPostingUses() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        DraftProposal first = drafts.proposeLines(
                shop.userId(),
                "sale",
                shop.customerId(),
                shop.godownId(),
                List.of(new DraftLineSpec(shop.productId(), BigDecimal.ONE, "box", Paise.fromRupees(1000), null)),
                DAY,
                AT
        );
        DraftProposal edited = drafts.edit(
                shop.userId(), first.id(), "sale", shop.customerId(), shop.productId(), shop.godownId(),
                new BigDecimal("2"), "box", Paise.fromRupees(1000), DAY, AT
        );

        drafts.approve(shop.userId(), edited.id(), edited.version(), edited.contentHash());

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("30");
    }

    @Test
    void aReceiptDraftPostsTheReceivedQuantity() {
        Shop shop = shop();
        UUID supplierId = parties.create(shop.userId(), "Gupta", PartyRole.SUPPLIER).getId();
        DraftProposal draft = drafts.propose(
                shop.userId(), "receive", supplierId, shop.productId(), shop.godownId(),
                new BigDecimal("5"), "box", null, DAY, AT
        );

        drafts.approve(shop.userId(), draft.id(), draft.version(), draft.contentHash());

        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("50");
    }

    @Test
    void editingOneLineKeepsTheOther() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        Product tape = catalog.registerProduct(
                shop.userId(), "TAPE-" + shop.userId().toString().substring(0, 8), "Tape", null, null, "piece", false
        );
        catalog.approveUnit(shop.userId(), tape.getId(), "box", new BigDecimal("10"));
        UUID tapeSession = counts.start(shop.userId(), shop.godownId(), AT, "tape");
        counts.propose(shop.userId(), tapeSession, tape.getId(), new BigDecimal("5"), "box", null);
        counts.confirm(shop.userId(), tapeSession, UUID.randomUUID());
        DraftProposal draft = drafts.proposeLines(
                shop.userId(),
                "sale",
                shop.customerId(),
                shop.godownId(),
                List.of(
                        new DraftLineSpec(shop.productId(), BigDecimal.ONE, "box", Paise.fromRupees(100), null),
                        new DraftLineSpec(tape.getId(), BigDecimal.ONE, "box", Paise.fromRupees(50), null)
                ),
                DAY,
                AT
        );
        assertThatThrownBy(() -> drafts.edit(
                shop.userId(), draft.id(), "sale", shop.customerId(), tape.getId(), shop.godownId(),
                new BigDecimal("3"), "box", Paise.fromRupees(50), DAY, AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessage("Which line should change?");

        DraftProposal edited = drafts.continueDraft(
                shop.userId(), draft.id(), "sale", null, tape.getId(), null,
                new BigDecimal("3"), "box", Paise.fromRupees(50), DAY, AT
        );
        drafts.approve(shop.userId(), edited.id(), edited.version(), edited.contentHash());

        assertThat(draftLines.findByDraftIdOrderByLineNumberAsc(draft.id())).hasSize(2);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(counts.balance(shop.userId(), tape.getId(), shop.godownId())).isEqualByComparingTo("20");
    }

    private DraftProposal sale(Shop shop, BigDecimal boxes, Long amountPaise) {
        return drafts.propose(
                shop.userId(),
                "sale",
                shop.customerId(),
                shop.productId(),
                shop.godownId(),
                boxes,
                "box",
                amountPaise,
                DAY,
                AT
        );
    }

    private void openBoxes(Shop shop, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "drafts");
        counts.propose(shop.userId(), sessionId, shop.productId(), boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Draft House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId,
                "DRF-" + userId.toString().substring(0, 8),
                "Office folder",
                null,
                null,
                "piece",
                false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "City Stores", PartyRole.CUSTOMER);
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
}
