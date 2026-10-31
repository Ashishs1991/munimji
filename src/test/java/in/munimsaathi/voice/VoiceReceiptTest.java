package in.munimsaathi.voice;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftLine;
import in.munimsaathi.draft.DraftLineRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.evidence.DocumentStore;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.interpretation.Transcriber;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.inventory.InsufficientStockException;
import in.munimsaathi.ledger.Paise;
import in.munimsaathi.ledger.PostedDocumentRepository;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
class VoiceReceiptTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 1);
    private static final Instant AT = Instant.parse("2026-08-01T04:30:00Z");
    private static final String TRANSCRIPT = "Agarwal ko ek box folder aur paanch box tape, godown 1";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @MockBean
    private Transcriber transcriber;

    @MockBean
    private LanguageModel model;

    @Autowired
    private VoiceReceipts voices;

    @Autowired
    private DraftBook drafts;

    @Autowired
    private DraftLineRepository draftLines;

    @Autowired
    private DocumentStore documents;

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
    private PostedDocumentRepository posted;

    @Test
    void anUnresolvedLineStaysOnTheDraftAndFiveBoxesStayBoxes() {
        Shop shop = shop(false);
        open(shop, shop.folderId(), new BigDecimal("5"));
        byte[] audio = "ogg-voice".getBytes(StandardCharsets.UTF_8);
        when(transcriber.transcribe(audio, "audio/ogg")).thenReturn(TRANSCRIPT);
        when(model.complete(TRANSCRIPT)).thenReturn(twoLines("5"));

        VoiceReceipts.VoiceCapture capture = voices.capture(shop.userId(), "audio/ogg", "note.ogg", audio, DAY, AT);
        List<DraftLine> lines = draftLines.findByDraftIdOrderByLineNumberAsc(capture.draft().id());

        assertThat(capture.transcript()).isEqualTo(TRANSCRIPT);
        assertThat(documents.read(shop.userId(), capture.evidenceId()).content()).isEqualTo(audio);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).getUnresolved()).isEqualTo("unit");
        assertThat(lines.get(1).getDisplayQuantity()).isEqualByComparingTo("5");
        assertThat(lines.get(1).getUnitLabel()).isEqualTo("box");
        assertThat(capture.draft().status()).isEqualTo(DraftStatus.NEEDS_CLARIFICATION);
        assertThatThrownBy(() -> drafts.approve(
                shop.userId(), capture.draft().id(), capture.draft().version(), capture.draft().contentHash()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(counts.balance(shop.userId(), shop.folderId(), shop.godownId())).isEqualByComparingTo("50");
        assertThat(counts.balance(shop.userId(), shop.tapeId(), shop.godownId())).isEqualByComparingTo("0");
        assertThat(posted.findByOperationId(capture.draft().id())).isEmpty();
    }

    @Test
    void fiveBoxesUseTheConfirmedConversionAndBothLinesPostTogether() {
        Shop shop = shop(true);
        open(shop, shop.folderId(), new BigDecimal("5"));
        open(shop, shop.tapeId(), new BigDecimal("5"));
        byte[] audio = "ogg-priced".getBytes(StandardCharsets.UTF_8);
        when(transcriber.transcribe(audio, "audio/ogg")).thenReturn(TRANSCRIPT);
        when(model.complete(TRANSCRIPT)).thenReturn(twoLines("5"));

        VoiceReceipts.VoiceCapture capture = voices.capture(shop.userId(), "audio/ogg", "note.ogg", audio, DAY, AT);
        List<DraftLine> lines = draftLines.findByDraftIdOrderByLineNumberAsc(capture.draft().id());

        assertThat(lines.get(1).getUnresolved()).isNull();
        assertThat(lines.get(1).getDisplayQuantity()).isEqualByComparingTo("5");
        assertThat(lines.get(1).getUnitLabel()).isEqualTo("box");
        assertThat(capture.draft().status()).isEqualTo(DraftStatus.AWAITING_APPROVAL);

        drafts.approve(shop.userId(), capture.draft().id(), capture.draft().version(), capture.draft().contentHash());
        drafts.approve(shop.userId(), capture.draft().id(), capture.draft().version(), capture.draft().contentHash());

        assertThat(counts.balance(shop.userId(), shop.folderId(), shop.godownId())).isEqualByComparingTo("40");
        assertThat(counts.balance(shop.userId(), shop.tapeId(), shop.godownId())).isEqualByComparingTo("0");
    }

    @Test
    void oneLineThatCannotPostLeavesEveryLineUnposted() {
        Shop shop = shop(true);
        open(shop, shop.folderId(), BigDecimal.ONE);
        byte[] audio = "ogg-short".getBytes(StandardCharsets.UTF_8);
        when(transcriber.transcribe(audio, "audio/ogg")).thenReturn(TRANSCRIPT);
        when(model.complete(TRANSCRIPT)).thenReturn(twoLines("5"));

        VoiceReceipts.VoiceCapture capture = voices.capture(shop.userId(), "audio/ogg", "note.ogg", audio, DAY, AT);

        assertThatThrownBy(() -> drafts.approve(
                shop.userId(), capture.draft().id(), capture.draft().version(), capture.draft().contentHash()
        )).isInstanceOf(InsufficientStockException.class);
        assertThat(counts.balance(shop.userId(), shop.folderId(), shop.godownId())).isEqualByComparingTo("10");
        assertThat(counts.balance(shop.userId(), shop.tapeId(), shop.godownId())).isEqualByComparingTo("0");
        assertThat(posted.findByOperationId(capture.draft().id())).isEmpty();
    }

    private static String twoLines(String tapeQuantity) {
        return """
                {"intent":"sale","party_name":"Agarwal","location_code":"g1","lines":[
                  {"product_name":"Office folder","quantity":"1","unit":"box","amount_paise":%d},
                  {"product_name":"Tape","quantity":"%s","unit":"box","amount_paise":%d}
                ]}
                """.formatted(Paise.fromRupees(100), tapeQuantity, Paise.fromRupees(500));
    }

    private void open(Shop shop, UUID productId, BigDecimal boxes) {
        UUID sessionId = counts.start(shop.userId(), shop.godownId(), AT, "voice-" + productId);
        counts.propose(shop.userId(), sessionId, productId, boxes, "box", null);
        counts.confirm(shop.userId(), sessionId, UUID.randomUUID());
    }

    private Shop shop(boolean tapeHasBoxes) {
        UUID userId = UUID.randomUUID();
        Business business = businesses.save(new Business(UUID.randomUUID(), "City Stores", "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        Godown godown = godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        Product folder = catalog.registerProduct(
                userId, "VF-" + userId.toString().substring(0, 8), "Office folder", null, null, "piece", false
        );
        catalog.approveUnit(userId, folder.getId(), "box", new BigDecimal("10"));
        Product tape = catalog.registerProduct(
                userId, "VT-" + userId.toString().substring(0, 8), "Tape", null, null, "piece", false
        );
        if (tapeHasBoxes) {
            catalog.approveUnit(userId, tape.getId(), "box", new BigDecimal("10"));
        }
        Party customer = parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
        return new Shop(userId, godown.getId(), folder.getId(), tape.getId(), customer.getId());
    }

    private record Shop(UUID userId, UUID godownId, UUID folderId, UUID tapeId, UUID customerId) {
    }
}
