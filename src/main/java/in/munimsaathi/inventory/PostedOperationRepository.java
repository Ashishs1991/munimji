package in.munimsaathi.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PostedOperationRepository extends JpaRepository<PostedOperation, UUID> {
}
