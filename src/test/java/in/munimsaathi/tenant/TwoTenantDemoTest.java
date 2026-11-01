package in.munimsaathi.tenant;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.jobs.Job;
import in.munimsaathi.jobs.JobRepository;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.location.LocationBook;
import in.munimsaathi.reminder.ReminderBook;
import in.munimsaathi.reminder.ReminderKind;
import in.munimsaathi.reminder.ReminderTask;
import in.munimsaathi.reminder.ReminderTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TwoTenantDemoTest {

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
    private TenantContext tenants;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartyBook parties;

    @Autowired
    private PostingService posting;

    @Autowired
    private LocationBook locations;

    @Autowired
    private CountService counts;

    @Autowired
    private ReminderBook reminders;

    @Autowired
    private ReminderTaskRepository reminderTasks;

    @Autowired
    private JobRepository jobs;

    @Test
    void bothBusinessesKeepTheRecordedPathAndAgarwalDoesNotLeak() {
        UUID ownerId = UUID.randomUUID();
        Business city = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        Business other = businesses.save(new Business(UUID.randomUUID(), "Other House", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), city.getId(), ownerId, MembershipRole.OWNER));
        memberships.save(new Membership(UUID.randomUUID(), other.getId(), ownerId, MembershipRole.OWNER));

        assertThatThrownBy(() -> catalog.registerProduct(ownerId, "A", "Agarwal bag", null, null, "box", false))
                .isInstanceOf(ActiveBusinessRequiredException.class);

        tenants.selectBusiness(ownerId, city.getId());
        Books cityBooks = books(ownerId, "City");
        runCityPath(ownerId, cityBooks);

        tenants.selectBusiness(ownerId, other.getId());
        Books otherBooks = books(ownerId, "Other");
        runOtherPath(ownerId, otherBooks);

        assertThat(otherBooks.agarwal().getId()).isNotEqualTo(cityBooks.agarwal().getId());
        assertThat(parties.findByName(ownerId, "Agarwal")).singleElement()
                .extracting(Party::getId)
                .isEqualTo(otherBooks.agarwal().getId());
        assertThat(posting.customerReceivablePaise(ownerId, otherBooks.agarwal().getId())).isEqualTo(Paise.fromRupees(50));
        assertThat(counts.balance(ownerId, otherBooks.agarwalBox().getId(), otherBooks.godown1().getId()))
                .isEqualByComparingTo("1");

        tenants.selectBusiness(ownerId, city.getId());
        assertThat(parties.findByName(ownerId, "Agarwal")).singleElement()
                .extracting(Party::getId)
                .isEqualTo(cityBooks.agarwal().getId());
        assertThat(locations.businessQuantity(ownerId, cityBooks.agarwalBox().getId(), cityBooks.godown3().getId()))
                .isEqualByComparingTo("0");
        assertThat(locations.businessQuantity(ownerId, cityBooks.guptaBox().getId(), cityBooks.godown3().getId()))
                .isEqualByComparingTo("2");
        assertThat(locations.businessQuantity(ownerId, cityBooks.guptaBox().getId(), cityBooks.godown1().getId()))
                .isEqualByComparingTo("1");
        assertThat(posting.customerReceivablePaise(ownerId, cityBooks.agarwal().getId())).isEqualTo(Paise.fromRupees(300));
        assertThat(counts.balance(ownerId, otherBooks.agarwalBox().getId(), otherBooks.godown1().getId()))
                .isEqualByComparingTo("0");

        ReminderTask cityReminder = reminderTasks.findById(cityBooks.reminderId()).orElseThrow();
        assertThat(cityReminder.getBusinessId()).isEqualTo(city.getId());
        Job cityJob = jobs.findAll().stream()
                .filter(job -> cityBooks.reminderId().toString().equals(job.getPayload()))
                .findFirst()
                .orElseThrow();
        assertThat(cityJob.getBusinessId()).isEqualTo(city.getId());

        tenants.selectBusiness(ownerId, other.getId());
        Job leased = jobs.findById(cityJob.getId()).orElseThrow();
        leased.lease("demo", cityReminder.getScheduledAt().plus(Duration.ofMinutes(5)));
        jobs.saveAndFlush(leased);
        reminders.dispatch(leased, "demo", cityReminder.getScheduledAt());
        assertThat(reminderTasks.findById(cityBooks.reminderId()).orElseThrow().getOutcome()).isEqualTo("dashboard");
        assertThat(posting.customerReceivablePaise(ownerId, otherBooks.agarwal().getId())).isEqualTo(Paise.fromRupees(50));
    }

    private void runCityPath(UUID ownerId, Books books) {
        catalog.approveAlias(ownerId, books.agarwalBox().getId(), "Agarwal box");
        catalog.approveAlias(ownerId, books.guptaBox().getId(), "Gupta box");
        assertThat(catalog.resolveApproved(ownerId, "Agarwal box").orElseThrow().getId())
                .isEqualTo(books.agarwalBox().getId())
                .isNotEqualTo(books.agarwal().getId());

        locations.remember(ownerId, UUID.randomUUID(), "Agarwal box", books.godown3().getId(), books.agarwalBox().getId(), AT);
        assertThat(locations.locate(ownerId, books.agarwalBox().getId())).singleElement().satisfies(place -> {
            assertThat(place.locationId()).isEqualTo(books.godown3().getId());
            assertThat(place.currentlyStocked()).isFalse();
        });

        posting.receiveGoods(ownerId, UUID.randomUUID(), books.supplier().getId(), books.agarwalBox().getId(), books.godown3().getId(), BigDecimal.ONE, "box", DAY, AT);
        posting.receiveGoods(ownerId, UUID.randomUUID(), books.supplier().getId(), books.guptaBox().getId(), books.godown3().getId(), new BigDecimal("5"), "box", DAY, AT.plusSeconds(1));
        posting.sell(ownerId, UUID.randomUUID(), books.agarwal().getId(), books.agarwalBox().getId(), books.godown3().getId(), BigDecimal.ONE, "box", Paise.fromRupees(100), DAY, AT.plusSeconds(2));
        UUID guptaSale = posting.sell(ownerId, UUID.randomUUID(), books.agarwal().getId(), books.guptaBox().getId(), books.godown3().getId(), new BigDecimal("3"), "box", Paise.fromRupees(300), DAY, AT.plusSeconds(3));
        UUID transfer = UUID.randomUUID();
        locations.transfer(ownerId, transfer, books.guptaBox().getId(), books.godown3().getId(), books.godown1().getId(), BigDecimal.ONE, "box", AT.plusSeconds(4));
        locations.transfer(ownerId, transfer, books.guptaBox().getId(), books.godown3().getId(), books.godown1().getId(), BigDecimal.ONE, "box", AT.plusSeconds(4));

        assertThat(locations.businessQuantity(ownerId, books.agarwalBox().getId(), books.godown3().getId())).isEqualByComparingTo("0");
        assertThat(locations.businessQuantity(ownerId, books.guptaBox().getId(), books.godown3().getId())).isEqualByComparingTo("1");
        assertThat(locations.businessQuantity(ownerId, books.guptaBox().getId(), books.godown1().getId())).isEqualByComparingTo("1");
        assertThat(posting.customerReceivablePaise(ownerId, books.agarwal().getId())).isEqualTo(Paise.fromRupees(400));

        UUID correction = UUID.randomUUID();
        posting.acceptReturn(ownerId, correction, books.agarwal().getId(), guptaSale, books.guptaBox().getId(), books.godown3().getId(), BigDecimal.ONE, "box", Paise.fromRupees(100), true, "one box back", DAY, AT.plusSeconds(5));
        posting.acceptReturn(ownerId, correction, books.agarwal().getId(), guptaSale, books.guptaBox().getId(), books.godown3().getId(), BigDecimal.ONE, "box", Paise.fromRupees(100), true, "one box back", DAY, AT.plusSeconds(5));
        assertThat(locations.businessQuantity(ownerId, books.guptaBox().getId(), books.godown3().getId())).isEqualByComparingTo("2");
        assertThat(posting.customerReceivablePaise(ownerId, books.agarwal().getId())).isEqualTo(Paise.fromRupees(300));

        UUID reminderId = reminders.propose(ownerId, ReminderKind.OWNER_REMINDER, books.agarwal().getId(), null, null, LocalDate.of(2099, 11, 17), LocalTime.of(10, 0), "city reminder", false, null, false);
        reminders.approve(ownerId, reminderId);
        books.reminder(reminderId);
    }

    private void runOtherPath(UUID ownerId, Books books) {
        posting.receiveGoods(ownerId, UUID.randomUUID(), books.supplier().getId(), books.agarwalBox().getId(), books.godown1().getId(), new BigDecimal("2"), "box", DAY, AT);
        posting.sell(ownerId, UUID.randomUUID(), books.agarwal().getId(), books.agarwalBox().getId(), books.godown1().getId(), BigDecimal.ONE, "box", Paise.fromRupees(50), DAY, AT.plusSeconds(1));
        locations.remember(ownerId, UUID.randomUUID(), "Agarwal box", books.godown1().getId(), books.agarwalBox().getId(), AT.plusSeconds(2));
        assertThat(locations.locate(ownerId, books.agarwalBox().getId())).singleElement()
                .extracting(place -> place.locationId())
                .isEqualTo(books.godown1().getId());
        assertThat(counts.balance(ownerId, books.agarwalBox().getId(), books.godown1().getId())).isEqualByComparingTo("1");

        UUID reminderId = reminders.propose(ownerId, ReminderKind.OWNER_REMINDER, books.agarwal().getId(), null, null, LocalDate.of(2099, 12, 1), LocalTime.of(11, 0), "other reminder", false, null, true);
        reminders.approve(ownerId, reminderId);
    }

    private Books books(UUID ownerId, String prefix) {
        Godown godown3 = godowns.save(new Godown(UUID.randomUUID(), tenants.currentBusinessId(ownerId), "g3", "Godown 3"));
        Godown godown1 = godowns.save(new Godown(UUID.randomUUID(), tenants.currentBusinessId(ownerId), "g1", "Godown 1"));
        Product agarwalBox = catalog.registerProduct(ownerId, prefix + "-A", "Agarwal bag", null, null, "box", false);
        Product guptaBox = catalog.registerProduct(ownerId, prefix + "-G", "Gupta bag", null, null, "box", false);
        Party supplier = parties.create(ownerId, "Mill", PartyRole.SUPPLIER);
        Party agarwal = parties.create(ownerId, "Agarwal", PartyRole.CUSTOMER);
        return new Books(godown3, godown1, agarwalBox, guptaBox, supplier, agarwal);
    }

    private static final class Books {
        private final Godown godown3;
        private final Godown godown1;
        private final Product agarwalBox;
        private final Product guptaBox;
        private final Party supplier;
        private final Party agarwal;
        private UUID reminderId;

        private Books(Godown godown3, Godown godown1, Product agarwalBox, Product guptaBox, Party supplier, Party agarwal) {
            this.godown3 = godown3;
            this.godown1 = godown1;
            this.agarwalBox = agarwalBox;
            this.guptaBox = guptaBox;
            this.supplier = supplier;
            this.agarwal = agarwal;
        }

        private Godown godown3() {
            return godown3;
        }

        private Godown godown1() {
            return godown1;
        }

        private Product agarwalBox() {
            return agarwalBox;
        }

        private Product guptaBox() {
            return guptaBox;
        }

        private Party supplier() {
            return supplier;
        }

        private Party agarwal() {
            return agarwal;
        }

        private UUID reminderId() {
            return reminderId;
        }

        private void reminder(UUID reminderId) {
            this.reminderId = reminderId;
        }
    }
}
