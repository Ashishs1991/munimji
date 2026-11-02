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
public class PendingBalanceQuestions {
    private final JdbcTemplate jdbc;
    private final TenantContext tenants;

    public PendingBalanceQuestions(JdbcTemplate jdbc, TenantContext tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @Transactional
    public void save(UUID userId, String name, String side, Long amount) {
        jdbc.update("""
            insert into pending_balance_questions (business_id, actor_id, party_name, balance_side, amount_paise, expires_at)
            values (?, ?, ?, ?, ?, ?)
            on conflict (business_id, actor_id) do update set party_name = excluded.party_name,
                balance_side = excluded.balance_side, amount_paise = excluded.amount_paise, expires_at = excluded.expires_at
            """, tenants.currentBusinessId(userId), userId, name, side, amount,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.HOURS)));
    }

    @Transactional(readOnly = true)
    public Optional<Question> get(UUID userId) {
        return jdbc.query("""
            select party_name, balance_side, amount_paise from pending_balance_questions
            where business_id = ? and actor_id = ? and expires_at > ?
            """, (row, index) -> new Question(row.getString(1), row.getString(2), (Long) row.getObject(3)),
                tenants.currentBusinessId(userId), userId, Timestamp.from(Instant.now())).stream().findFirst();
    }

    @Transactional
    public void clear(UUID userId) {
        jdbc.update("delete from pending_balance_questions where business_id = ? and actor_id = ?",
                tenants.currentBusinessId(userId), userId);
    }

    public record Question(String partyName, String side, Long amountPaise) {}
}
