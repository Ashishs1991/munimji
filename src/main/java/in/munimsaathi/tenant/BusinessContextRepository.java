package in.munimsaathi.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessContextRepository extends JpaRepository<BusinessContext, UUID> {
}
