package in.munimsaathi.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "businesses")
public class Business {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false, length = 3)
    private String currency;

    protected Business() {
    }

    public Business(UUID id, String name, String timezone, String currency) {
        this.id = id;
        this.name = name;
        this.timezone = timezone;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTimezone() {
        return timezone;
    }
}
