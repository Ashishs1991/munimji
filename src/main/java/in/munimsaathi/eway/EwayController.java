package in.munimsaathi.eway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam UUID userId,
            @PathVariable UUID invoiceId,
            @RequestBody EwayDetails details
    ) {
        return book.view(userId, book.prepare(userId, invoiceId, details));
    }

    @PostMapping("/api/eway-drafts/{id}/approve")
    public EwayDraftView approve(@RequestParam UUID userId, @PathVariable UUID id) {
        book.approve(userId, id);
        return book.view(userId, id);
    }

    @PostMapping("/api/eway-drafts/{id}/submit")
    public EwayDraftView submit(@RequestParam UUID userId, @PathVariable UUID id) {
        return book.submit(userId, id);
    }

    @PostMapping("/api/eway-drafts/{id}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@RequestParam UUID userId, @PathVariable UUID id) {
        book.cancel(userId, id);
        return ResponseEntity.ok(Map.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> rejected(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", exception.getMessage()));
    }
}
