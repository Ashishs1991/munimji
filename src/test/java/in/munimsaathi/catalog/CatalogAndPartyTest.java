package in.munimsaathi.catalog;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CatalogAndPartyTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Test
    void sizeAndBrandStayDistinctAndSimilarNamesAreNotMerged() {
        UUID userId = owner();
        Product small = catalog.registerProduct(userId, "BAG-12", "Plastic bag", "Local", "12x18", "piece", false);
        Product large = catalog.registerProduct(userId, "BAG-16", "Plastic bag", "Local", "16x20", "piece", false);

        assertThat(small.getId()).isNotEqualTo(large.getId());
        assertThat(small.getVariant()).isEqualTo("12x18");
        assertThat(large.getVariant()).isEqualTo("16x20");
        assertThat(catalog.candidates(userId, "Plastic bag")).extracting(Product::getSku)
                .containsExactly("BAG-12", "BAG-16");
        assertThat(catalog.resolveApproved(userId, "Plastic bag")).isEmpty();
    }

    @Test
    void approvedAliasAndPackConversionAreStoredExactly() {
        UUID userId = owner();
        Product folders = catalog.registerProduct(userId, "FOLD-1", "Office folder", null, null, "piece", false);
        catalog.approveAlias(userId, folders.getId(), "  Agarwal   Box ");
        catalog.approveUnit(userId, folders.getId(), "box", new BigDecimal("10"));

        assertThat(catalog.resolveApproved(userId, "agarwal box").orElseThrow().getSku()).isEqualTo("FOLD-1");
        assertThat(catalog.resolveApproved(userId, "FOLD-1").orElseThrow().getId()).isEqualTo(folders.getId());
        assertThat(catalog.toBaseQuantity(userId, folders.getId(), new BigDecimal("12"), "box"))
                .isEqualByComparingTo("120");
        assertThat(folders.getTaxConfigurationStatus()).isEqualTo("UNCONFIGURED");
    }

    @Test
    void hindiAliasResolvesOnlyAfterApproval() {
        UUID userId = owner();
        Product bags = catalog.registerProduct(userId, "BAG-A", "Agarwal bags", null, null, "piece", false);

        assertThat(catalog.resolveApproved(userId, "अग्रवाल बॉक्स")).isEmpty();
        catalog.approveAlias(userId, bags.getId(), "अग्रवाल बॉक्स");
        assertThat(catalog.resolveApproved(userId, "अग्रवाल बॉक्स").orElseThrow().getSku()).isEqualTo("BAG-A");
    }

    @Test
    void unapprovedUnitAndFractionalPiecesAreRejected() {
        UUID userId = owner();
        Product folders = catalog.registerProduct(userId, "FOLD-2", "Office folder", null, null, "piece", false);

        assertThatThrownBy(() -> catalog.toBaseQuantity(userId, folders.getId(), BigDecimal.ONE, "box"))
                .isInstanceOf(UnknownUnitException.class);
        assertThatThrownBy(() -> catalog.toBaseQuantity(userId, folders.getId(), new BigDecimal("1.5"), "piece"))
                .isInstanceOf(IndivisibleQuantityException.class);
        assertThatThrownBy(() -> catalog.toBaseQuantity(userId, folders.getId(), BigDecimal.ZERO, "piece"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quantity must be greater than zero.");
        assertThatThrownBy(() -> catalog.toBaseQuantity(userId, folders.getId(), new BigDecimal("-1"), "piece"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quantity must be greater than zero.");
    }

    @Test
    void halfBoxIsAcceptedWhenTheBasePiecesAreWhole() {
        UUID userId = owner();
        Product film = catalog.registerProduct(userId, "FILM", "Film roll", null, null, "kg", true);
        catalog.approveUnit(userId, film.getId(), "bag", new BigDecimal("2.5"));

        assertThat(catalog.toBaseQuantity(userId, film.getId(), new BigDecimal("1.5"), "bag"))
                .isEqualByComparingTo("3.75");
    }

    @Test
    void sameSkuCannotBeRegisteredTwice() {
        UUID userId = owner();
        catalog.registerProduct(userId, "BAG-12", "Plastic bag", null, "12x18", "piece", false);

        assertThatThrownBy(() -> catalog.registerProduct(userId, "BAG-12", "Other bag", null, "16x20", "piece", false))
                .isInstanceOf(DuplicateSkuException.class);
    }

    @Test
    void aliasDoesNotLeakAcrossBusinesses() {
        UUID firstUser = owner();
        UUID secondUser = owner();
        Product product = catalog.registerProduct(firstUser, "BAG-A", "Agarwal bags", null, null, "piece", false);
        catalog.approveAlias(firstUser, product.getId(), "agarwal box");

        assertThat(catalog.resolveApproved(secondUser, "agarwal box")).isEmpty();
        assertThat(catalog.candidates(secondUser, "Agarwal")).isEmpty();
    }

    @Test
    void samePartyNameStaysSeparateAndRoleDoesNotChangeByItself() {
        UUID userId = owner();
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        Party supplier = parties.create(userId, "Agarwal", PartyRole.SUPPLIER);
        Party both = parties.create(userId, "Gupta", PartyRole.BOTH);

        assertThat(customer.getId()).isNotEqualTo(supplier.getId());
        assertThat(parties.findByName(userId, "agarwal")).extracting(Party::getRole)
                .containsExactlyInAnyOrder(PartyRole.CUSTOMER, PartyRole.SUPPLIER);
        assertThat(customer.getRole()).isEqualTo(PartyRole.CUSTOMER);
        assertThat(both.getRole()).isEqualTo(PartyRole.BOTH);

        parties.changeRole(userId, customer.getId(), PartyRole.BOTH);
        assertThat(parties.findByName(userId, "Agarwal"))
                .filteredOn(party -> party.getId().equals(customer.getId()))
                .extracting(Party::getRole)
                .containsExactly(PartyRole.BOTH);
        assertThat(supplier.getRole()).isEqualTo(PartyRole.SUPPLIER);
    }

    @Test
    void rewritingAnApprovedUnitIsRejected() {
        UUID userId = owner();
        Product folders = catalog.registerProduct(userId, "FOLD-3", "Office folder", null, null, "piece", false);
        catalog.approveUnit(userId, folders.getId(), "box", new BigDecimal("10"));

        assertThatThrownBy(() -> catalog.approveUnit(userId, folders.getId(), "box", new BigDecimal("12")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.toBaseQuantity(userId, folders.getId(), BigDecimal.ONE, "box"))
                .isEqualByComparingTo("10");
    }

    private UUID owner() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "Bag House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        return userId;
    }
}
