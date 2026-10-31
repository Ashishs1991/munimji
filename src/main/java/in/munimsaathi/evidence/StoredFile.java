package in.munimsaathi.evidence;

import java.util.UUID;

public record StoredFile(UUID id, UUID draftId, String mediaType, String fileName, String sha256, byte[] content) {
}
