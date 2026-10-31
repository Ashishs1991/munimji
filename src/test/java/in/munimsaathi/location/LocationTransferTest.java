package in.munimsaathi.location;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.InsufficientStockException;
import in.munimsaathi.ledger.Paise;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LocationTransferTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 1);
    private static final Instant AT = Instant.parse("2026-07-01T04:30:00Z");

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
    private PostingService posting;

    @Autowired
    private PostedDocumentRepository documents;

    @Autowired
    private LocationBook locations;

    @Test
    void agarwalBoxHasAMeaningAndGodownsKeepTheirOwnStock() {
        Shop shop = shop();
        Party agarwal = parties.create(shop.userId(), "Agarwal", PartyRole.CUSTOMER);
        catalog.approveAlias(shop.userId(), shop.agarwalBoxId(), "Agarwal box");
        catalog.approveAlias(shop.userId(), shop.guptaBoxId(), "Gupta box");

        Product resolved = catalog.resolveApproved(shop.userId(), "Agarwal box").orElseThrow();
        assertThat(resolved.getId()).isEqualTo(shop.agarwalBoxId());
        assertThat(resolved.getId()).isNotEqualTo(agarwal.getId());
        assertThat(posting.customerReceivablePaise(shop.userId(), agarwal.getId())).isZero();

        UUID remembered = UUID.randomUUID();
        locations.remember(shop.userId(), remembered, "Agarwal box", shop.godown3Id(), shop.agarwalBoxId(), AT);
        locations.remember(shop.userId(), remembered, "Agarwal box", shop.godown3Id(), shop.agarwalBoxId(), AT);
        assertThat(documents.findByOperationId(remembered)).isEmpty();
        assertThat(locations.businessQuantity(shop.userId(), shop.agarwalBoxId(), shop.godown3Id())).isEqualByComparingTo("0");
        assertThat(locations.locate(shop.userId(), shop.agarwalBoxId())).singleElement().satisfies(place -> {
            assertThat(place.locationId()).isEqualTo(shop.godown3Id());
            assertThat(place.available()).isEqualByComparingTo("0");
            assertThat(place.currentlyStocked()).isFalse();
        });

        posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.supplierId(), shop.agarwalBoxId(), shop.godown3Id(),
                BigDecimal.ONE, "box", DAY, AT
        );
        posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.supplierId(), shop.guptaBoxId(), shop.godown3Id(),
                new BigDecimal("5"), "box", DAY, AT.plusSeconds(1)
        );
        posting.sell(
                shop.userId(), UUID.randomUUID(), agarwal.getId(), shop.agarwalBoxId(), shop.godown3Id(),
                BigDecimal.ONE, "box", Paise.fromRupees(100), DAY, AT.plusSeconds(2)
        );
        posting.sell(
                shop.userId(), UUID.randomUUID(), agarwal.getId(), shop.guptaBoxId(), shop.godown3Id(),
                new BigDecimal("3"), "box", Paise.fromRupees(300), DAY, AT.plusSeconds(3)
        );

        assertThat(locations.businessQuantity(shop.userId(), shop.agarwalBoxId(), shop.godown3Id())).isEqualByComparingTo("0");
        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown3Id())).isEqualByComparingTo("2");
        assertThat(locations.locate(shop.userId(), shop.agarwalBoxId())).singleElement().satisfies(place -> {
            assertThat(place.locationId()).isEqualTo(shop.godown3Id());
            assertThat(place.currentlyStocked()).isFalse();
            assertThat(place.available()).isEqualByComparingTo("0");
        });

        UUID transfer = UUID.randomUUID();
        locations.transfer(
                shop.userId(), transfer, shop.guptaBoxId(), shop.godown3Id(), shop.godown1Id(),
                BigDecimal.ONE, "box", AT.plusSeconds(4)
        );
        locations.transfer(
                shop.userId(), transfer, shop.guptaBoxId(), shop.godown3Id(), shop.godown1Id(),
                BigDecimal.ONE, "box", AT.plusSeconds(4)
        );

        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown3Id())).isEqualByComparingTo("1");
        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown1Id())).isEqualByComparingTo("1");
        assertThat(locations.locate(shop.userId(), shop.guptaBoxId())).hasSize(2);
        assertThat(posting.customerReceivablePaise(shop.userId(), agarwal.getId())).isEqualTo(Paise.fromRupees(400));

        assertThatThrownBy(() -> locations.transfer(
                shop.userId(), UUID.randomUUID(), shop.guptaBoxId(), shop.godown3Id(), shop.godown1Id(),
                new BigDecimal("2"), "box", AT.plusSeconds(5)
        )).isInstanceOf(InsufficientStockException.class);
        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown3Id())).isEqualByComparingTo("1");
        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown1Id())).isEqualByComparingTo("1");

        locations.receiveCustomerOwned(
                shop.userId(), UUID.randomUUID(), shop.agarwalBoxId(), shop.godown3Id(),
                BigDecimal.ONE, "box", "customer box", AT.plusSeconds(6)
        );
        assertThat(locations.customerQuantity(shop.userId(), shop.agarwalBoxId(), shop.godown3Id())).isEqualByComparingTo("1");
        assertThat(locations.businessQuantity(shop.userId(), shop.agarwalBoxId(), shop.godown3Id())).isEqualByComparingTo("0");
        assertThatThrownBy(() -> posting.sell(
                shop.userId(), UUID.randomUUID(), agarwal.getId(), shop.agarwalBoxId(), shop.godown3Id(),
                BigDecimal.ONE, "box", Paise.fromRupees(100), DAY, AT.plusSeconds(7)
        )).isInstanceOf(InsufficientStockException.class);
        assertThat(locations.customerQuantity(shop.userId(), shop.agarwalBoxId(), shop.godown3Id())).isEqualByComparingTo("1");
        assertThat(locations.locate(shop.userId(), shop.agarwalBoxId()).get(0).currentlyStocked()).isFalse();
    }

    @Test
    void aTransferCannotUseAnotherBusinessGodown() {
        Shop shop = shop();
        Shop other = shop();
        posting.receiveGoods(
                shop.userId(), UUID.randomUUID(), shop.supplierId(), shop.guptaBoxId(), shop.godown3Id(),
                BigDecimal.ONE, "box", DAY, AT
        );

        assertThatThrownBy(() -> locations.transfer(
                shop.userId(), UUID.randomUUID(), shop.guptaBoxId(), shop.godown3Id(), other.godown1Id(),
                BigDecimal.ONE, "box", AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(locations.businessQuantity(shop.userId(), shop.guptaBoxId(), shop.godown3Id())).isEqualByComparingTo("1");
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Godown House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown3 = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g3", "Godown 3"));
        Godown godown1 = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product agarwalBox = catalog.registerProduct(userId, "A", "Agarwal bag", null, null, "box", false);
        Product guptaBox = catalog.registerProduct(userId, "G", "Gupta bag", null, null, "box", false);
        Party supplier = parties.create(userId, "Mill", PartyRole.SUPPLIER);
        return new Shop(userId, godown3.getId(), godown1.getId(), agarwalBox.getId(), guptaBox.getId(), supplier.getId());
    }

    private record Shop(
            UUID userId,
            UUID godown3Id,
            UUID godown1Id,
            UUID agarwalBoxId,
            UUID guptaBoxId,
            UUID supplierId
    ) {
    }
}
