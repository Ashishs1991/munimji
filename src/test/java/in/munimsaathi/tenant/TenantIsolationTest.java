package in.munimsaathi.tenant;

import in.munimsaathi.PostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TenantIsolationTest {

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
    private BusinessContextRepository contexts;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private GodownQuery godownQuery;

    @Test
    void singleMembershipSeesOnlyItsGodowns() {
        UUID userId = UUID.randomUUID();
        Business agarwalTraders = business("Agarwal Traders");
        Business guptaStores = business("Gupta Stores");
        memberships.save(membership(agarwalTraders.getId(), userId, MembershipRole.OWNER));
        godowns.save(godown(agarwalTraders.getId(), "g3", "Godown 3"));
        godowns.save(godown(guptaStores.getId(), "g3", "Other godown 3"));

        assertThat(godownQuery.listFor(userId))
                .extracting(Godown::getName)
                .containsExactly("Godown 3");
    }

    @Test
    void sharedOwnerMustSelectBeforeListing() {
        UUID userId = UUID.randomUUID();
        Business first = business("Bag House");
        Business second = business("Poly Pack");
        memberships.save(membership(first.getId(), userId, MembershipRole.OWNER));
        memberships.save(membership(second.getId(), userId, MembershipRole.OWNER));
        godowns.save(godown(first.getId(), "g1", "Shop"));
        godowns.save(godown(second.getId(), "g2", "Warehouse"));

        assertThatThrownBy(() -> godownQuery.listFor(userId))
                .isInstanceOf(ActiveBusinessRequiredException.class);
    }

    @Test
    void selectedBusinessHidesTheOtherTenant() {
        UUID userId = UUID.randomUUID();
        Business first = business("Bag House");
        Business second = business("Poly Pack");
        memberships.save(membership(first.getId(), userId, MembershipRole.OWNER));
        memberships.save(membership(second.getId(), userId, MembershipRole.OWNER));
        godowns.save(godown(first.getId(), "g1", "Shop"));
        godowns.save(godown(second.getId(), "g3", "Godown 3"));

        tenantContext.selectBusiness(userId, second.getId());

        assertThat(godownQuery.listFor(userId))
                .extracting(Godown::getCode)
                .containsExactly("g3");
        assertThat(contexts.findById(userId).orElseThrow().getContextVersion()).isEqualTo(1);
    }

    @Test
    void cannotSelectABusinessTheUserDoesNotBelongTo() {
        UUID userId = UUID.randomUUID();
        Business own = business("Bag House");
        Business other = business("Poly Pack");
        memberships.save(membership(own.getId(), userId, MembershipRole.OWNER));

        assertThatThrownBy(() -> tenantContext.selectBusiness(userId, other.getId()))
                .isInstanceOf(BusinessAccessDeniedException.class);
        assertThat(contexts.findById(userId)).isEmpty();
    }

    @Test
    void sameGodownCodeCanExistInBothBusinesses() {
        Business first = business("Bag House");
        Business second = business("Poly Pack");

        godowns.saveAndFlush(godown(first.getId(), "g3", "First"));
        godowns.saveAndFlush(godown(second.getId(), "g3", "Second"));

        assertThat(godowns.findByBusinessIdOrderByCodeAsc(first.getId())).hasSize(1);
        assertThat(godowns.findByBusinessIdOrderByCodeAsc(second.getId())).hasSize(1);
    }

    @Test
    void duplicateGodownCodeInOneBusinessIsRejected() {
        Business business = business("Bag House");
        godowns.saveAndFlush(godown(business.getId(), "g3", "First"));

        assertThatThrownBy(() -> godowns.saveAndFlush(godown(business.getId(), "g3", "Again")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateMembershipIsRejected() {
        UUID userId = UUID.randomUUID();
        Business business = business("Bag House");
        memberships.saveAndFlush(membership(business.getId(), userId, MembershipRole.OWNER));

        assertThatThrownBy(() -> memberships.saveAndFlush(membership(business.getId(), userId, MembershipRole.OPERATOR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Business business(String name) {
        return businesses.save(new Business(UUID.randomUUID(), name, "Asia/Kolkata", "INR"));
    }

    private Membership membership(UUID businessId, UUID userId, MembershipRole role) {
        return new Membership(UUID.randomUUID(), businessId, userId, role);
    }

    private Godown godown(UUID businessId, String code, String name) {
        return new Godown(UUID.randomUUID(), businessId, code, name);
    }
}
