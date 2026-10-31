package in.munimsaathi.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WhatsAppSenderRepository extends JpaRepository<WhatsAppSender, UUID> {

    Optional<WhatsAppSender> findByPhone(String phone);
}
