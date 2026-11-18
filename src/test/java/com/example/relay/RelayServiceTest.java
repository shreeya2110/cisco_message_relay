package com.example.relay;

import static com.example.relay.RelayException.Reason.*;
import static org.assertj.core.api.Assertions.*;

import com.example.relay.RelayProtocol.Delivery;
import com.example.relay.RelayProtocol.SendRequest;
import com.example.relay.RelayProtocol.Session;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RelayServiceTest {

    private final TestClock clock = new TestClock();
    private RelayService relay;
    private Session alice;
    private Session bob;

    @BeforeEach
    void setUp() {
        relay = new RelayService(new RelayProperties(3, 2, 32, 1024), clock);
        alice = relay.register("alice");
        bob = relay.register("bob");
    }

    @Test
    void exchangesMessagesInBothDirections() {
        var first = send(alice, "bob", "hello");
        var second = send(bob, "alice", "hi");

        var toBob = receive(bob);
        var toAlice = receive(alice);
        assertThat(toBob.messageId()).isEqualTo(first.messageId());
        assertThat(toBob.sender()).isEqualTo("alice");
        assertThat(toBob.acceptedAt()).isEqualTo(clock.instant());
        assertThat(toAlice.messageId()).isEqualTo(second.messageId());

        acknowledge(bob, toBob);
        acknowledge(alice, toAlice);
        assertThat(relay.receive("bob", bob.sessionId())).isEmpty();
        assertThat(relay.receive("alice", alice.sessionId())).isEmpty();
    }

    @Test
    void retainsOfflineMessagesAndRedeliversUnacknowledgedHeadAfterReconnect() {
        relay.disconnect("bob", bob.sessionId());
        var first = send(alice, "bob", "first");
        var second = send(alice, "bob", "second");
        bob = relay.register("bob");
        var original = receive(bob);
        assertThat(original.messageId()).isEqualTo(first.messageId());

        var previousSession = bob;
        bob = relay.register("bob");
        var redelivered = receive(bob);
        assertThat(redelivered.messageId()).isEqualTo(original.messageId());
        assertThat(redelivered.receipt()).isNotEqualTo(original.receipt());
        assertReason(STALE_SESSION, () -> acknowledge(previousSession, original));
        assertReason(INVALID_RECEIPT, () -> acknowledge(bob, original));
        assertReason(STALE_SESSION, () -> relay.disconnect("bob", previousSession.sessionId()));

        acknowledge(bob, redelivered);
        assertThat(receive(bob).messageId()).isEqualTo(second.messageId());
    }

    @Test
    void doesNotRedeliverUntilReconnectEvenAfterTimePasses() {
        send(alice, "bob", "first");
        send(alice, "bob", "second");
        var original = receive(bob);
        assertThat(relay.receive("bob", bob.sessionId())).isEmpty();
        clock.advance(Duration.ofDays(1));
        assertThat(relay.receive("bob", bob.sessionId())).isEmpty();

        // The original receipt remains valid until ACK or reconnect.
        acknowledge(bob, original);
        assertThat(receive(bob).body()).isEqualTo("second");
    }

    @Test
    void requiresDeliveryToCorrectRecipientBeforeAcknowledgement() {
        var sent = send(alice, "bob", "hello");
        assertReason(INVALID_RECEIPT, () -> relay.acknowledge("bob", bob.sessionId(),
                sent.messageId(), UUID.randomUUID()));
        var delivered = receive(bob);
        assertReason(MESSAGE_NOT_FOUND, () -> acknowledge(alice, delivered));
        acknowledge(bob, delivered);
        assertReason(MESSAGE_NOT_FOUND, () -> acknowledge(bob, delivered));
    }

    @Test
    void deduplicatesPendingSendsButRejectsConflictingReuseEvenWhenFull() {
        var first = send(alice, "bob", "hello");
        send(alice, "bob", "second");
        assertThat(relay.send("alice", alice.sessionId(), first).duplicate()).isTrue();
        assertReason(MESSAGE_CONFLICT, () -> relay.send("alice", alice.sessionId(),
                new SendRequest(first.messageId(), "bob", "changed")));
        assertReason(MESSAGE_CONFLICT, () -> relay.send("bob", bob.sessionId(), first));
        assertReason(MAILBOX_FULL, () -> send(alice, "bob", "third"));

        acknowledge(bob, receive(bob));
        assertThat(relay.send("alice", alice.sessionId(), first).duplicate()).isFalse();
    }

    @Test
    void boundsIdentitiesIncludingOfflineClientsWithoutBlockingReconnect() {
        var charlie = relay.register("charlie");
        relay.disconnect("charlie", charlie.sessionId());
        assertReason(CLIENT_LIMIT, () -> relay.register("dave"));
        assertThat(relay.register("charlie").sessionId()).isNotEqualTo(charlie.sessionId());
        assertReason(STALE_SESSION, () -> send(charlie, "bob", "hello"));
    }

    @Test
    void rejectsUnknownIdentitiesInvalidInputAndOversizedUtf8Messages() {
        assertReason(UNKNOWN_CLIENT, () -> send(alice, "unknown", "hello"));
        assertReason(INVALID_INPUT, () -> relay.register("bad name"));
        assertReason(INVALID_INPUT, () -> send(alice, "bob", " "));
        assertReason(MESSAGE_TOO_LARGE, () -> send(alice, "bob", "\u00e9".repeat(17)));
        assertReason(INVALID_INPUT, () -> relay.send("alice", alice.sessionId(),
                new SendRequest(null, "bob", "hello")));
    }

    @Test
    void concurrentSendsRespectCapacityAndKeepAcceptedMessages() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                    try {
                        return send(alice, "bob", "hello").messageId();
                    } catch (RelayException exception) {
                        assertThat(exception.reason()).isEqualTo(MAILBOX_FULL);
                        return null;
                    }
                }));
            }
            start.countDown();
            var accepted = new ArrayList<UUID>();
            for (var future : futures) {
                var id = future.get(3, TimeUnit.SECONDS);
                if (id != null) {
                    accepted.add(id);
                }
            }
            assertThat(accepted).hasSize(2);
            var delivered = new ArrayList<UUID>();
            for (int i = 0; i < 2; i++) {
                var message = receive(bob);
                delivered.add(message.messageId());
                acknowledge(bob, message);
            }
            assertThat(delivered).containsExactlyInAnyOrderElementsOf(accepted);
            assertThat(relay.receive("bob", bob.sessionId())).isEmpty();
        }
    }

    private SendRequest send(Session sender, String recipient, String body) {
        var request = new SendRequest(UUID.randomUUID(), recipient, body);
        relay.send(sender.clientId(), sender.sessionId(), request);
        return request;
    }

    private Delivery receive(Session recipient) {
        return relay.receive(recipient.clientId(), recipient.sessionId()).orElseThrow();
    }

    private void acknowledge(Session recipient, Delivery delivery) {
        relay.acknowledge(recipient.clientId(), recipient.sessionId(), delivery.messageId(), delivery.receipt());
    }

    private void assertReason(RelayException.Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RelayException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
