package com.example.relay;

import static com.example.relay.RelayProtocol.*;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RelayController {

    private final RelayService relay;

    public RelayController(RelayService relay) {
        this.relay = relay;
    }

    @PutMapping("/clients/{clientId}")
    public Session register(@PathVariable String clientId) {
        return relay.register(clientId);
    }

    @DeleteMapping("/clients/{clientId}/session")
    public ResponseEntity<Void> disconnect(@PathVariable String clientId,
                                          @RequestHeader(SESSION_ID_HEADER) UUID sessionId) {
        relay.disconnect(clientId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages")
    public ResponseEntity<SendResult> send(@RequestHeader(CLIENT_ID_HEADER) String clientId,
                                          @RequestHeader(SESSION_ID_HEADER) UUID sessionId,
                                          @Valid @RequestBody SendRequest request) {
        return ResponseEntity.accepted().body(relay.send(clientId, sessionId, request));
    }

    @GetMapping("/messages/next")
    public ResponseEntity<Delivery> receive(@RequestHeader(CLIENT_ID_HEADER) String clientId,
                                            @RequestHeader(SESSION_ID_HEADER) UUID sessionId) {
        return relay.receive(clientId, sessionId)
                .map(delivery -> ResponseEntity.ok().body(delivery))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/messages/{messageId}/ack")
    public ResponseEntity<Void> acknowledge(@RequestHeader(CLIENT_ID_HEADER) String clientId,
                                           @RequestHeader(SESSION_ID_HEADER) UUID sessionId,
                                           @PathVariable UUID messageId,
                                           @Valid @RequestBody AckRequest request) {
        relay.acknowledge(clientId, sessionId, messageId, request.receipt());
        return ResponseEntity.noContent().build();
    }
}
