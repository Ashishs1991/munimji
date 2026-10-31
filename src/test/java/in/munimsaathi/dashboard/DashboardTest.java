package in.munimsaathi.dashboard;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.DraftBook;
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
import in.munimsaathi.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final LocalDate NEXT = LocalDate.of(2026, 8, 2);
    private static final Instant AT = Instant.parse("2026-08-01T04:30:00Z");
    private static final Instant NEXT_AT = Instant.parse("2026-08-02T04:30:00Z");

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
    private DraftBook drafts;

    @Autowired
    private OwnerDashboard dashboard;

    @Autowired
    private TenantContext tenants;

    @Autowired
    private LowStockAlertRepository alerts;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overviewMatchesTheBooksForThatDateAndAThresholdIsNotAForecast() throws Exception {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        UUID saleId = posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
        posting.receivePayment(
                shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(400), "cash", DAY, AT
        );
        Product tape = catalog.registerProduct(shop.userId(), "TAPE-" + shop.userId().toString().substring(0, 8), "Tape", null, null, "piece", false);
        catalog.approveUnit(shop.userId(), tape.getId(), "box", new BigDecimal("10"));
        Party supplier = parties.create(shop.userId(), "Gupta", PartyRole.SUPPLIER);
        posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), supplier.getId(), tape.getId(), shop.godownId(),
                BigDecimal.ONE, "box", DAY, AT
        );
        drafts.propose(
                shop.userId(), "sale", shop.customerId(), shop.productId(), shop.godownId(),
                null, "box", Paise.fromRupees(1000), DAY, AT
        );
        drafts.propose(
                shop.userId(), "sale", shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );

        Overview today = dashboard.overview(shop.userId(), DAY);
        Overview yesterday = dashboard.overview(shop.userId(), DAY.minusDays(1));

        assertThat(today.businessName()).isEqualTo("City Stores");
        assertThat(today.forecastAvailable()).isFalse();
        assertThat(today.stock()).extracting(Overview.StockRow::productName, Overview.StockRow::godownName, Overview.StockRow::quantityText)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Office folder", "Godown 1", "40"),
                        org.assertj.core.groups.Tuple.tuple("Tape", "Godown 1", "10")
                );
        assertThat(today.unknownExpiry()).containsExactly("Office folder", "Tape");
        assertThat(today.salesPaise()).isEqualTo(Paise.fromRupees(1000));
        assertThat(today.receiptPaise()).isEqualTo(Paise.fromRupees(400));
        assertThat(today.returnPaise()).isZero();
        assertThat(today.receivablePaise()).isEqualTo(Paise.fromRupees(600));
        assertThat(today.payablePaise()).isZero();
        assertThat(today.saleDocumentIds()).containsExactly(saleId);
        assertThat(today.clarificationDrafts()).isEqualTo(1);
        assertThat(today.awaitingApproval()).isEqualTo(1);
        assertThat(today.financePending()).isEqualTo(1);
        assertThat(yesterday.stock()).isEmpty();
        assertThat(yesterday.salesPaise()).isZero();
        assertThat(yesterday.receivablePaise()).isZero();

        posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), NEXT, NEXT_AT
        );
        Overview stillToday = dashboard.overview(shop.userId(), DAY);
        Overview nextDay = dashboard.overview(shop.userId(), NEXT);
        assertThat(stillToday.salesPaise()).isEqualTo(Paise.fromRupees(1000));
        assertThat(row(stillToday, "Office folder")).isEqualTo("40");
        assertThat(nextDay.salesPaise()).isEqualTo(Paise.fromRupees(1000));
        assertThat(row(nextDay, "Office folder")).isEqualTo("30");
        assertThat(nextDay.receivablePaise()).isEqualTo(Paise.fromRupees(1600));

        dashboard.setThreshold(shop.userId(), shop.productId(), new BigDecimal("45"));
        Overview alerted = dashboard.overview(shop.userId(), DAY);
        dashboard.overview(shop.userId(), DAY);
        assertThat(alerted.lowStock()).extracting(Overview.AlertRow::productName).containsExactly("Office folder");
        assertThat(alerted.forecastAvailable()).isFalse();
        assertThat(alerts.countByBusinessIdAndProductId(shop.businessId(), shop.productId())).isEqualTo(1);

        String page = "/dashboard?userId=" + shop.userId() + "&date=2026-08-01";
        mockMvc.perform(post("/dashboard/threshold")
                        .param("userId", shop.userId().toString())
                        .param("productId", shop.productId().toString())
                        .param("minimum", "45")
                        .param("date", "2026-08-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(page));
        mockMvc.perform(get("/dashboard").param("userId", shop.userId().toString()).param("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("600.00")))
                .andExpect(content().string(containsString("1000.00")))
                .andExpect(content().string(containsString("Office folder")))
                .andExpect(content().string(containsString("This is a threshold alert, not a forecast.")))
                .andExpect(content().string(containsString("No forecast is available.")))
                .andExpect(content().string(not(containsString("Profit"))));

        dashboard.setThreshold(shop.userId(), shop.productId(), new BigDecimal("10"));
        Overview cleared = dashboard.overview(shop.userId(), DAY);
        assertThat(cleared.lowStock()).isEmpty();
        assertThat(alerts.countByBusinessIdAndProductId(shop.businessId(), shop.productId())).isEqualTo(1);
    }

    @Test
    void aSharedOwnerMustSelectTheBusinessBeforeFiguresAppear() {
        Shop shop = shop();
        openBoxes(shop, new BigDecimal("5"));
        Business other = businesses.save(new Business(UUID.randomUUID(), "Other House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), other.getId(), shop.userId(), MembershipRole.OWNER));

        Overview hidden = dashboard.overview(shop.userId(), DAY);
        assertThat(hidden.selectionRequired()).isTrue();
        assertThat(hidden.stock()).isEmpty();
        assertThat(hidden.salesPaise()).isZero();

        tenants.selectBusiness(shop.userId(), shop.businessId());
        Overview selected = dashboard.overview(shop.userId(), DAY);
        assertThat(selected.selectionRequired()).isFalse();
        assertThat(row(selected, "Office folder")).isEqualTo("50");

        tenants.selectBusiness(shop.userId(), other.getId());
        Overview otherBooks = dashboard.overview(shop.userId(), DAY);
        assertThat(otherBooks.businessName()).isEqualTo("Other House");
        assertThat(otherBooks.stock()).isEmpty();
    }

    private static String row(Overview overview, String productName) {
        return overview.stock().stream()
                .filter(stock -> stock.productName().equals(productName))
                .findFirst()
                .orElseThrow()
                .quantityText();
    }

    private void openBoxes(Shop shop, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "dashboard");
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
                "DASH-" + userId.toString().substring(0, 8),
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
