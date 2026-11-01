package in.munimsaathi.eway;

import in.munimsaathi.config.OwnerSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class EwayController {

    private final EwayBook book;

    public EwayController(EwayBook book) {
        this.book = book;
    }

    @PostMapping("/api/invoices/{invoiceId}/eway-draft")
    public EwayDraftView prepare(
            HttpServletRequest request,
            @RequestParam(required = false) UUID userId,
            @PathVariable UUID invoiceId,
            @RequestBody EwayDetails details
    ) {
        UUID ownerId = owner(request, userId);
        return book.view(ownerId, book.prepare(ownerId, invoiceId, details));
    }

    @PostMapping("/api/eway-drafts/{id}/approve")
    public EwayDraftView approve(HttpServletRequest request, @RequestParam(required = false) UUID userId, @PathVariable UUID id) {
        UUID ownerId = owner(request, userId);
        book.approve(ownerId, id);
        return book.view(ownerId, id);
    }

    @PostMapping("/api/eway-drafts/{id}/submit")
    public EwayDraftView submit(HttpServletRequest request, @RequestParam(required = false) UUID userId, @PathVariable UUID id) {
        UUID ownerId = owner(request, userId);
        return book.submit(ownerId, id);
    }

    @PostMapping("/api/eway-drafts/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(HttpServletRequest request, @RequestParam(required = false) UUID userId, @PathVariable UUID id) {
        UUID ownerId = owner(request, userId);
        book.cancel(ownerId, id);
        return ResponseEntity.ok(Map.of());
    }

    private static UUID owner(HttpServletRequest request, UUID requested) {
        Object value = request.getAttribute(OwnerSessionFilter.OWNER_ID);
        if (!(value instanceof UUID ownerId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in is required.");
        }
        if (requested != null && !requested.equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That user is not the signed-in owner.");
        }
        return ownerId;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> rejected(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", exception.getMessage()));
    }
}
