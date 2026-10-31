package in.munimsaathi.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReminderTaskRepository extends JpaRepository<ReminderTask, UUID> {
}
