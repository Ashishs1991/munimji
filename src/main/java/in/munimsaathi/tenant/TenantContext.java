package in.munimsaathi.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class TenantContext {

    private final MembershipRepository memberships;
    private final BusinessContextRepository contexts;

    public TenantContext(MembershipRepository memberships, BusinessContextRepository contexts) {
        this.memberships = memberships;
        this.contexts = contexts;
    }

    @Transactional
    public UUID currentBusinessId(UUID userId) {
        List<Membership> membershipsForUser = memberships.findByAuthUserId(userId);
        if (membershipsForUser.isEmpty()) {
            throw new BusinessAccessDeniedException();
        }
        return contexts.findById(userId)
                .map(context -> {
                    boolean stillMember = membershipsForUser.stream()
                            .anyMatch(membership -> membership.getBusinessId().equals(context.getBusinessId()));
                    if (!stillMember) {
                        throw new BusinessAccessDeniedException();
                    }
                    return context.getBusinessId();
                })
                .orElseGet(() -> {
                    if (membershipsForUser.size() != 1) {
                        throw new ActiveBusinessRequiredException();
                    }
                    return membershipsForUser.get(0).getBusinessId();
                });
    }

    @Transactional
    public void selectBusiness(UUID userId, UUID businessId) {
        if (!memberships.existsByAuthUserIdAndBusinessId(userId, businessId)) {
            throw new BusinessAccessDeniedException();
        }
        BusinessContext context = contexts.findById(userId).orElseGet(() -> new BusinessContext(userId));
        context.select(businessId, Instant.now());
        contexts.save(context);
    }

    @Transactional
    public <T> T inBusiness(UUID userId, UUID businessId, Supplier<T> work) {
        if (businessId == null) {
            return work.get();
        }
        if (!memberships.existsByAuthUserIdAndBusinessId(userId, businessId)) {
            throw new BusinessAccessDeniedException();
        }
        BusinessContext context = contexts.findById(userId).orElse(null);
        UUID previous = context == null ? null : context.getBusinessId();
        boolean created = context == null;
        if (created) {
            context = new BusinessContext(userId);
        }
        context.select(businessId, Instant.now());
        contexts.saveAndFlush(context);
        try {
            return work.get();
        } finally {
            if (created) {
                contexts.delete(context);
                contexts.flush();
            } else if (previous != null && !previous.equals(businessId)) {
                context.select(previous, Instant.now());
                contexts.saveAndFlush(context);
            }
        }
    }
}
