package in.munimsaathi.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "whatsapp_senders")
public class WhatsAppSender {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String phone;

    protected WhatsAppSender() {
    }

    public WhatsAppSender(UUID id, UUID businessId, UUID userId, String phone) {
        this.id = id;
        this.businessId = businessId;
        this.userId = userId;
        this.phone = phone;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getUserId() {
        return userId;
    }
}
