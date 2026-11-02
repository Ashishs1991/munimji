package in.munimsaathi.interpretation;

import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationMemory {

    private static final int CAP = 500;

    private final TenantContext tenants;
    private final ConversationTurnRepository turns;

    public ConversationMemory(TenantContext tenants, ConversationTurnRepository turns) {
        this.tenants = tenants;
        this.turns = turns;
    }

    @Transactional
    public void remember(UUID userId, String role, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        String text = body.length() > CAP ? body.substring(0, CAP) : body;
        turns.save(new ConversationTurn(
                UUID.randomUUID(),
                tenants.currentBusinessId(userId),
                userId,
                role,
                text,
                Instant.now()
        ));
    }

    @Transactional(readOnly = true)
    public String recent(UUID userId) {
        List<ConversationTurn> rows = turns.findTop12ByBusinessIdAndActorIdOrderByCreatedAtDesc(tenants.currentBusinessId(userId), userId);
        List<String> lines = new ArrayList<>();
        for (int index = Math.min(rows.size(), 10) - 1; index >= 0; index--) {
            ConversationTurn row = rows.get(index);
            lines.add(row.getRole() + ": " + row.getBody());
        }
        return String.join("\n", lines);
    }
}
