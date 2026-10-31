package in.munimsaathi.forecast;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.location.LocationBook;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ForecastTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 28);

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
    private LocationBook locations;

    @Autowired
    private ForecastService forecasts;

    @Test
    void thinHistoryReturnsInsufficientHistory() {
        Shop shop = shop();
        forecasts.cover(shop.userId(), START, START.plusDays(2));
        sell(shop, START);
        sell(shop, START.plusDays(1));
        sell(shop, START.plusDays(2));

        Forecast forecast = forecasts.forecast(shop.userId(), shop.productId(), START.plusDays(2));

        assertThat(forecast.eligibility()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(forecast.forecastQuantity()).isNull();
        assertThat(forecast.limitation()).isEqualTo("insufficient reliable history");
        assertThat(forecast.horizonDays()).isEqualTo(7);
    }

    @Test
    void aSevenDayBaselineUsesCoveredSalesOnly() {
        Shop shop = shop();
        forecasts.cover(shop.userId(), START, END);
        open(shop, at(START, 4));
        posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.supplierId(), shop.productId(), shop.godownId(),
                new BigDecimal("5"), "box", START.plusDays(1), at(START.plusDays(1), 9)
        );
        locations.transfer(
                shop.userId(), UUID.randomUUID(), shop.productId(), shop.godownId(), shop.otherGodownId(),
                BigDecimal.ONE, "box", at(START.plusDays(2), 9)
        );
        LocalDate[] saleDays = {
                START, START.plusDays(3), START.plusDays(7), START.plusDays(11),
                START.plusDays(15), START.plusDays(19), START.plusDays(23), END
        };
        UUID lastSale = null;
        for (LocalDate day : saleDays) {
            lastSale = sell(shop, day);
        }
        posting.acceptReturn(
                shop.userId(), UUID.randomUUID(), shop.customerId(), lastSale, shop.productId(), shop.godownId(),
                BigDecimal.ONE, "piece", Paise.fromRupees(1), true, "one piece back", END, at(END, 11)
        );

        Forecast forecast = forecasts.forecast(shop.userId(), shop.productId(), END);

        assertThat(forecast.eligibility()).isEqualTo("PROVISIONAL");
        assertThat(forecast.forecastQuantity()).isEqualByComparingTo("20");
        assertThat(forecast.grossQuantity()).isEqualByComparingTo("80");
        assertThat(forecast.returnQuantity()).isEqualByComparingTo("1");
        assertThat(forecast.positiveDays()).isEqualTo(8);
        assertThat(forecast.limitation()).contains("provisional");
    }

    @Test
    void reorderStopsWhenLeadTimeOrInboundIsUnknown() {
        Shop shop = shop();
        forecasts.cover(shop.userId(), START, END);
        open(shop, at(START, 4));
        for (int offset : new int[] {0, 3, 7, 11, 15, 19, 23, 27}) {
            sell(shop, START.plusDays(offset));
        }

        assertThat(forecasts.reorder(shop.userId(), shop.productId(), END, null, BigDecimal.ZERO).missing())
                .isEqualTo("lead time");
        assertThat(forecasts.reorder(shop.userId(), shop.productId(), END, null, BigDecimal.ZERO).quantity())
                .isNull();
        assertThat(forecasts.reorder(shop.userId(), shop.productId(), END, 28, null).missing())
                .isEqualTo("inbound stock");
        assertThat(forecasts.reorder(shop.userId(), shop.productId(), END, 28, null).quantity())
                .isNull();

        ReorderSuggestion suggestion = forecasts.reorder(
                shop.userId(), shop.productId(), END, 28, BigDecimal.ZERO
        );
        assertThat(suggestion.missing()).isNull();
        assertThat(suggestion.quantity()).isEqualByComparingTo("60");
    }

    private UUID sell(Shop shop, LocalDate day) {
        return posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(100), day, at(day, 10)
        );
    }

    private void open(Shop shop, Instant at) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), at, "forecast");
        counts.propose(shop.userId(), sessionId, shop.productId(), new BigDecimal("10"), "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(KOLKATA).toInstant();
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Godown other = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g2", "Godown 2"));
        Product product = catalog.registerProduct(
                userId, "FC-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        Party supplier = parties.create(userId, "Gupta", PartyRole.SUPPLIER);
        open(new Shop(userId, godown.getId(), other.getId(), product.getId(), customer.getId(), supplier.getId()), at(START.minusDays(1), 9));
        return new Shop(userId, godown.getId(), other.getId(), product.getId(), customer.getId(), supplier.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID otherGodownId, UUID productId, UUID customerId, UUID supplierId) {
    }
}
