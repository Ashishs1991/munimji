package in.munimsaathi.catalog;

import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PartyBook {

    private final TenantContext tenants;
    private final PartyRepository parties;

    public PartyBook(TenantContext tenants, PartyRepository parties) {
        this.tenants = tenants;
        this.parties = parties;
    }

    @Transactional
    public Party create(UUID userId, String displayName, PartyRole role) {
        UUID businessId = tenants.currentBusinessId(userId);
        return parties.save(new Party(
                UUID.randomUUID(),
                businessId,
                displayName.trim(),
                Names.normalize(displayName),
                role,
                null
        ));
    }

    @Transactional(readOnly = true)
    public List<Party> findByName(UUID userId, String displayName) {
        UUID businessId = tenants.currentBusinessId(userId);
        return parties.findByBusinessIdAndNormalizedNameOrderByDisplayNameAsc(
                businessId,
                Names.normalize(displayName)
        );
    }

    @Transactional
    public Party changeRole(UUID userId, UUID partyId, PartyRole role) {
        UUID businessId = tenants.currentBusinessId(userId);
        Party party = parties.findById(partyId).orElseThrow(() -> new IllegalArgumentException("Unknown party."));
        if (!party.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown party.");
        }
        party.changeRole(role);
        return party;
    }
}
