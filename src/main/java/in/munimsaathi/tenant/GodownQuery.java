package in.munimsaathi.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class GodownQuery {

    private final TenantContext tenantContext;
    private final GodownRepository godowns;

    public GodownQuery(TenantContext tenantContext, GodownRepository godowns) {
        this.tenantContext = tenantContext;
        this.godowns = godowns;
    }

    @Transactional(readOnly = true)
    public List<Godown> listFor(UUID userId) {
        return godowns.findByBusinessIdOrderByCodeAsc(tenantContext.currentBusinessId(userId));
    }
}
