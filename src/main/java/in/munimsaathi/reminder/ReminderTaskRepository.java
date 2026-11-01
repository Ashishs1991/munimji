package in.munimsaathi.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReminderTaskRepository extends JpaRepository<ReminderTask, UUID> {

    List<ReminderTask> findByBusinessIdAndStatusIn(UUID businessId, Collection<ReminderStatus> statuses);
}
