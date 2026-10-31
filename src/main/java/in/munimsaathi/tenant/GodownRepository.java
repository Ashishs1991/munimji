package in.munimsaathi.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GodownRepository extends JpaRepository<Godown, UUID> {

    List<Godown> findByBusinessIdOrderByCodeAsc(UUID businessId);
}
