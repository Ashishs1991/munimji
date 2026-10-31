package in.munimsaathi.whatsapp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhatsAppController {

    private final WhatsAppIngress ingress;

    public WhatsAppController(WhatsAppIngress ingress) {
        this.ingress = ingress;
    }

    @GetMapping("/webhooks/whatsapp")
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge
    ) {
        if (!ingress.challengeMatches(mode, token)) {
            return ResponseEntity.status(403).body("rejected");
        }
        return ResponseEntity.ok(challenge);
    }

    @PostMapping("/webhooks/whatsapp")
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body
    ) {
        WhatsAppIngress.WebhookResult result = ingress.receive(signature, body);
        String bodyText = result.id() == null ? "" : result.id().toString();
        return ResponseEntity.status(result.status()).body(bodyText);
    }
}
