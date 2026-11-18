package com.example.relay;

import static com.example.relay.RelayException.Reason.*;
import static com.example.relay.RelayProtocol.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RelayService {

    private final Map<String, Mailbox> clients = new HashMap<>();
    private final RelayProperties limits;
    private final Clock clock;

    public RelayService(RelayProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    
    public synchronized Session register(String clientId) {
        validateClientId(clientId);
        var mailbox = clients.get(clientId);
        if (mailbox == null) {
            if (clients.size() >= limits.maxClients()) {
                throw new RelayException(CLIENT_LIMIT, "The client registry is full");
            }
            mailbox = new Mailbox();
            clients.put(clientId, mailbox);
        }
        mailbox.sessionId = UUID.randomUUID();
        mailbox.messages.values().forEach(PendingMessage::release);
        return new Session(clientId, mailbox.sessionId);
    }

    public synchronized void disconnect(String clientId, UUID sessionId) {
        var mailbox = requireSession(clientId, sessionId);
        mailbox.sessionId = null;
        mailbox.messages.values().forEach(PendingMessage::release);
    }

    public synchronized SendResult send(String sender, UUID sessionId, SendRequest request) {
        requireSession(sender, sessionId);
        if (request == null || request.messageId() == null || request.body() == null
                || request.body().isBlank()) {
            throw new RelayException(INVALID_INPUT, "A message ID and non-blank body are required");
        }
        validateClientId(request.recipient());
        if (request.body().getBytes(StandardCharsets.UTF_8).length > limits.maxMessageBytes()) {
            throw new RelayException(MESSAGE_TOO_LARGE, "Message exceeds the UTF-8 byte limit");
        }
        var mailbox = requireClient(request.recipient());
        var existing = mailbox.messages.get(request.messageId());
        if (existing != null) {
            if (!existing.sender.equals(sender) || !existing.body.equals(request.body())) {
                throw new RelayException(MESSAGE_CONFLICT, "Message ID is already in use in this mailbox");
            }
            return new SendResult(request.messageId(), true);
        }
        if (mailbox.messages.size() >= limits.mailboxCapacity()) {
            throw new RelayException(MAILBOX_FULL, "Recipient mailbox is full");
        }
        mailbox.messages.put(request.messageId(),
                new PendingMessage(request.messageId(), sender, request.body(), clock.instant()));
        return new SendResult(request.messageId(), false);
    }

    public synchronized Optional<Delivery> receive(String clientId, UUID sessionId) {
        var mailbox = requireSession(clientId, sessionId);
        if (mailbox.messages.isEmpty()) {
            return Optional.empty();
        }
        var head = mailbox.messages.firstEntry().getValue();
        if (head.receipt != null) {
            return Optional.empty();
        }
        head.receipt = UUID.randomUUID();
        return Optional.of(new Delivery(head.id, head.sender, clientId, head.body,
                head.acceptedAt, head.receipt));
    }

    public synchronized void acknowledge(String clientId, UUID sessionId, UUID messageId, UUID receipt) {
        var mailbox = requireSession(clientId, sessionId);
        var message = mailbox.messages.get(messageId);
        if (message == null) {
            throw new RelayException(MESSAGE_NOT_FOUND, "Message is not pending in this mailbox");
        }
        if (receipt == null || !receipt.equals(message.receipt)) {
            throw new RelayException(INVALID_RECEIPT, "Receipt does not match the latest delivery");
        }
        mailbox.messages.remove(messageId);
    }

    private Mailbox requireSession(String clientId, UUID sessionId) {
        var mailbox = requireClient(clientId);
        if (sessionId == null || !sessionId.equals(mailbox.sessionId)) {
            throw new RelayException(STALE_SESSION, "Register again to obtain a current session");
        }
        return mailbox;
    }

    private Mailbox requireClient(String clientId) {
        validateClientId(clientId);
        var mailbox = clients.get(clientId);
        if (mailbox == null) {
            throw new RelayException(UNKNOWN_CLIENT, "Client is not registered");
        }
        return mailbox;
    }

    private void validateClientId(String clientId) {
        if (clientId == null || !clientId.matches(CLIENT_ID_PATTERN)) {
            throw new RelayException(INVALID_INPUT, "Client ID must match " + CLIENT_ID_PATTERN);
        }
    }

    private static final class Mailbox {
        private UUID sessionId;
        private final LinkedHashMap<UUID, PendingMessage> messages = new LinkedHashMap<>();
    }

    private static final class PendingMessage {
        private final UUID id;
        private final String sender;
        private final String body;
        private final Instant acceptedAt;
        private UUID receipt;

        private PendingMessage(UUID id, String sender, String body, Instant acceptedAt) {
            this.id = id;
            this.sender = sender;
            this.body = body;
            this.acceptedAt = acceptedAt;
        }

        private void release() {
            receipt = null;
        }
    }
}
