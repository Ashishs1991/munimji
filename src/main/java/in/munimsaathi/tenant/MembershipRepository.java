package in.munimsaathi.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByAuthUserId(UUID authUserId);

    boolean existsByAuthUserIdAndBusinessId(UUID authUserId, UUID businessId);
}
