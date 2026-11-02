package in.munimsaathi.interpretation;

import in.munimsaathi.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class DialogueState {
    private final JdbcTemplate jdbc;
    private final TenantContext tenants;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;

    public DialogueState(JdbcTemplate jdbc, TenantContext tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public Optional<State> get(UUID userId) {
        return jdbc.query("""
                select draft_id, question, proposed_name, proposed_unit, draft_version from dialogue_state
                where business_id = ? and actor_id = ? and expires_at > ?
                """, (row, index) -> new State(row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4), row.getLong(5)),
                tenants.currentBusinessId(userId), userId, Timestamp.from(Instant.now())).stream().findFirst();
    }

    @Transactional
    public void save(UUID userId, UUID draftId, String question, String name, String unit) {
        // The JPA draft must exist before JDBC stores its foreign key.
        entities.flush();
        long version = entities.find(in.munimsaathi.draft.Draft.class, draftId).getVersion();
        jdbc.update("""
                insert into dialogue_state (business_id, actor_id, draft_id, draft_version, question, proposed_name, proposed_unit, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (business_id, actor_id) do update set draft_id = excluded.draft_id,
                    draft_version = excluded.draft_version, question = excluded.question, proposed_name = excluded.proposed_name,
                    proposed_unit = excluded.proposed_unit, expires_at = excluded.expires_at
                """, tenants.currentBusinessId(userId), userId, draftId, version, question, name, unit,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)));
    }

    @Transactional
    public void clear(UUID userId) {
        jdbc.update("delete from dialogue_state where business_id = ? and actor_id = ?", tenants.currentBusinessId(userId), userId);
    }

    @Transactional
    public void recordTurn(UUID userId, String source, String modelReply, UUID draftId, String kind) {
        entities.flush();
        jdbc.update("""
                insert into interpretation_turns (id, business_id, actor_id, source_text, model_reply, draft_id, resolved_kind, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenants.currentBusinessId(userId), userId, source, modelReply,
                draftId, kind, Timestamp.from(Instant.now()));
    }

    public record State(UUID draftId, String question, String name, String unit, long version) {}
}
