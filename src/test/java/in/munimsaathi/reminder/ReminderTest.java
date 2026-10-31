package in.munimsaathi.reminder;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.jobs.JobWorker;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReminderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);
    private static final Instant AT = Instant.parse("2026-10-31T04:30:00Z");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

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
    private ReminderBook reminders;

    @Autowired
    private ReminderTaskRepository tasks;

    @Autowired
    private JobWorker worker;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @MockBean
    private MessageSender sender;

    @Test
    void ownerRemindersAndCustomerSendsStayDifferentTasks() {
        when(sender.send(anyString(), anyString(), anyString()))
                .thenReturn(new ProviderReceipt("accepted", "wamid.consenting-test"));
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-01-01T04:30:00Z");
        LocalDateTime local = LocalDateTime.ofInstant(when, KOLKATA);

        UUID ownerId = reminders.propose(
                shop.userId(), ReminderKind.OWNER_REMINDER, shop.customerId(), null, null,
                local.toLocalDate(), local.toLocalTime(), "remind me", false, null, true
        );
        UUID customerId = reminders.propose(
                shop.userId(), ReminderKind.CUSTOMER_SEND, shop.customerId(), saleId, "919876543210",
                local.toLocalDate(), local.toLocalTime(), "payment reminder", true, "payment_due", false
        );

        assertThat(tasks.findById(ownerId).orElseThrow().getKind()).isEqualTo(ReminderKind.OWNER_REMINDER);
        assertThat(tasks.findById(customerId).orElseThrow().getKind()).isEqualTo(ReminderKind.CUSTOMER_SEND);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));

        reminders.approve(shop.userId(), ownerId);
        reminders.approve(shop.userId(), customerId);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));

        worker.poll(when.plusSeconds(30));
        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(ownerId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.DASHBOARD);
        ReminderTask sent = tasks.findById(customerId).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(ReminderStatus.PROVIDER_ACCEPTED);
        assertThat(sent.getProviderStatus()).isEqualTo("accepted");
        assertThat(sent.getProviderId()).isEqualTo("wamid.consenting-test");
        verify(sender, times(1)).send("919876543210", "payment_due", "payment reminder");
    }

    @Test
    void fullPaymentSuppressesTheSend() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-02-01T04:30:00Z");
        UUID taskId = customerTask(shop, saleId, when);
        reminders.approve(shop.userId(), taskId);
        posting.receivePayment(shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(1000), "cash", DAY, AT);

        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.SUPPRESSED_PAID);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isZero();
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aChangedBalanceNeedsANewApproval() {
        when(sender.send(anyString(), anyString(), anyString()))
                .thenReturn(new ProviderReceipt("accepted", "wamid.after-revision"));
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-03-01T04:30:00Z");
        UUID taskId = customerTask(shop, saleId, when);
        reminders.approve(shop.userId(), taskId);
        posting.receivePayment(shop.userId(), UUID.randomUUID(), shop.customerId(), saleId, Paise.fromRupees(800), "cash", DAY, AT);

        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.NEEDS_REAPPROVAL);
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(200));
        verify(sender, never()).send(anyString(), anyString(), anyString());

        reminders.approve(shop.userId(), taskId);
        assertThat(tasks.findById(taskId).orElseThrow().getApprovedAmountPaise()).isEqualTo(Paise.fromRupees(200));
        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(taskId).orElseThrow().getProviderStatus()).isEqualTo("accepted");
        verify(sender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void cancellationWinsBeforeDispatch() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-04-01T04:30:00Z");
        UUID taskId = customerTask(shop, saleId, when);
        reminders.approve(shop.userId(), taskId);
        reminders.cancel(shop.userId(), taskId);

        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.CANCELED);
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aJobPastTheGraceWindowIsNotSent() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-05-01T04:30:00Z");
        UUID taskId = customerTask(shop, saleId, when);
        reminders.approve(shop.userId(), taskId);

        worker.poll(when.plus(Duration.ofMinutes(20)));

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.MISSED);
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aRestartDoesNotSendAnAttemptWhoseProviderStatusIsUnknown() {
        Shop shop = shop();
        UUID saleId = sell(shop);
        Instant when = Instant.parse("2098-06-01T04:30:00Z");
        UUID taskId = customerTask(shop, saleId, when);
        reminders.approve(shop.userId(), taskId);
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "update reminder_tasks set status = 'SENDING', send_key = :key where id = :id")
                .setParameter("key", taskId + ":1")
                .setParameter("id", taskId)
                .executeUpdate());

        worker.poll(when.plusSeconds(30));
        worker.poll(when.plusSeconds(30));

        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(ReminderStatus.OUTCOME_UNKNOWN);
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void november17KeepsItsYearAndAMissingTimeIsRefused() {
        Shop shop = shop();
        UUID saleId = sell(shop);

        assertThatThrownBy(() -> reminders.propose(
                shop.userId(), ReminderKind.CUSTOMER_SEND, shop.customerId(), saleId, "919876543210",
                LocalDate.of(2026, 11, 17), null, "payment reminder", true, "payment_due", false
        )).isInstanceOf(IllegalArgumentException.class).hasMessage("Ask for the sending time.");

        UUID taskId = reminders.propose(
                shop.userId(), ReminderKind.CUSTOMER_SEND, shop.customerId(), saleId, "919876543210",
                LocalDate.of(2026, 11, 17), LocalTime.of(10, 0), "payment reminder", true, "payment_due", false
        );
        ReminderTask task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getScheduledAt()).isEqualTo(Instant.parse("2026-11-17T04:30:00Z"));
        assertThat(task.getLocalDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(posting.customerReceivablePaise(shop.userId(), shop.customerId())).isEqualTo(Paise.fromRupees(1000));

        assertThatThrownBy(() -> reminders.propose(
                shop.userId(), ReminderKind.OWNER_REMINDER, shop.customerId(), null, null,
                LocalDate.of(2026, 10, 1), LocalTime.of(10, 0), "late", false, null, true
        )).isInstanceOf(IllegalArgumentException.class).hasMessage("That time has already passed.");
    }

    private UUID customerTask(Shop shop, UUID saleId, Instant when) {
        LocalDateTime local = LocalDateTime.ofInstant(when, KOLKATA);
        return reminders.propose(
                shop.userId(), ReminderKind.CUSTOMER_SEND, shop.customerId(), saleId, "919876543210",
                local.toLocalDate(), local.toLocalTime(), "payment reminder", true, "payment_due", false
        );
    }

    private UUID sell(Shop shop) {
        openBoxes(shop);
        return posting.sell(
                shop.userId(), UUID.randomUUID(), shop.customerId(), shop.productId(), shop.godownId(),
                BigDecimal.ONE, "box", Paise.fromRupees(1000), DAY, AT
        );
    }

    private void openBoxes(Shop shop) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "reminders");
        counts.propose(shop.userId(), sessionId, shop.productId(), new BigDecimal("5"), "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop() {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product product = catalog.registerProduct(
                userId, "REM-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false
        );
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        return new Shop(userId, business.getId(), godown.getId(), product.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID businessId, UUID godownId, UUID productId, UUID customerId) {
    }
}
