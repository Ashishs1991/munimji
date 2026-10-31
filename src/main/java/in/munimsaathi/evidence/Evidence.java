package in.munimsaathi.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "draft_id")
    private UUID draftId;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "file_name")
    private String fileName;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Evidence() {
    }

    public Evidence(
            UUID id,
            UUID businessId,
            UUID draftId,
            String mediaType,
            String fileName,
            String sha256,
            byte[] content,
            Instant createdAt
    ) {
        this.id = id;
        this.businessId = businessId;
        this.draftId = draftId;
        this.mediaType = mediaType;
        this.fileName = fileName;
        this.sha256 = sha256;
        this.content = content;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getDraftId() {
        return draftId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSha256() {
        return sha256;
    }

    public byte[] getContent() {
        return content;
    }
}
