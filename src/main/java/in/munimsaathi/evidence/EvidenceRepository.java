package in.munimsaathi.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {
}
