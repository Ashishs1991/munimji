package in.munimsaathi.imports;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.Paise;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HistoricalImportTest {

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
    private PostingService posting;

    @Autowired
    private ImportBook imports;

    @Autowired
    private ImportBatchRepository batches;

    @Autowired
    private ImportRowRepository rows;

    @Test
    void activationPostsTheSnapshotOnceAndLeavesHistoryOutOfLiveStock() {
        Shop shop = shop();
        byte[] file = export("10", "500000", "2");

        UUID first = imports.stage(shop.userId(), "year.csv", file);
        UUID again = imports.stage(shop.userId(), "year.csv", file);
        assertThat(again).isEqualTo(first);
        assertThat(batches.countByBusinessId(shop.businessId())).isEqualTo(1);
        assertThat(batches.findById(first).orElseThrow().getHistoricalSales()).isEqualTo(1);
        assertThat(batches.findById(first).orElseThrow().getQuarantinedRows()).isZero();

        imports.activate(shop.userId(), first, shop.godownId(), DAY, AT);
        imports.activate(shop.userId(), first, shop.godownId(), DAY, AT);

        assertThat(batches.findById(first).orElseThrow().getStatus()).isEqualTo(ImportStatus.ACTIVE);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("10");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(5000));
        assertThat(rows.findByImportIdOrderByRowNumberAsc(first).stream()
                .filter(row -> "sale".equals(row.getRowClass()))
                .findFirst()
                .orElseThrow()
                .getStatus()).isEqualTo(ImportRowStatus.HISTORICAL);

        byte[] changed = export("99", "500000", "2");
        UUID second = imports.stage(shop.userId(), "year-revised.csv", changed);
        assertThat(second).isNotEqualTo(first);
        assertThat(batches.findById(second).orElseThrow().getStatus()).isEqualTo(ImportStatus.STAGED);
        assertThat(batches.findById(second).orElseThrow().getOpeningPieces()).isEqualByComparingTo("99");
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("10");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(5000));

        imports.activate(shop.userId(), second, shop.godownId(), DAY, AT);
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("10");
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(5000));
    }

    @Test
    void anUnresolvedRowStaysQuarantinedAndDoesNotPost() {
        Shop shop = shop();
        String csv = """
                row_class,source_id,name,quantity,amount_paise
                opening_stock,,Office folder,10,
                """;
        UUID importId = imports.stage(shop.userId(), "bad.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(batches.findById(importId).orElseThrow().getQuarantinedRows()).isEqualTo(1);
        assertThat(rows.findByImportIdOrderByRowNumberAsc(importId).get(0).getError()).isEqualTo("source id");
        assertThatThrownBy(() -> imports.activate(shop.userId(), importId, shop.godownId(), DAY, AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unresolved rows stay quarantined.");
        assertThat(counts.balance(shop.userId(), shop.productId(), shop.godownId())).isEqualByComparingTo("0");
    }

    private static byte[] export(String openingPieces, String openingPaise, String historicalPieces) {
        String csv = """
                row_class,source_id,name,quantity,amount_paise
                product,folder,Office folder,,
                party,agarwal,Agarwal,,
                opening_stock,open-folder,Office folder,%s,
                opening_receivable,open-agarwal,Agarwal,,%s
                sale,hist-1,Office folder,%s,200000
                """.formatted(openingPieces, openingPaise, historicalPieces);
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId, "IMP-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false
        );
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
}
