package com.example.relay;

import static com.example.relay.RelayProtocol.*;
import static org.assertj.core.api.Assertions.*;

import com.example.relay.client.RelayClient;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"relay.max-message-bytes=32", "relay.max-request-bytes=1024", "relay.mailbox-capacity=2"})
@Timeout(15)
class RelayHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RestClient.Builder builder;

    @Test
    void realRestClientsExchangeOfflineMessagesAndRedeliverAfterDisconnect() {
        try (var alice = new RelayClient(server(), "http-alice")) {
            try (var bob = new RelayClient(server(), "http-bob")) {
                assertThat(bob.receive()).isEmpty();
            }
            var id = UUID.randomUUID();
            assertThat(alice.send(id, "http-bob", "offline").duplicate()).isFalse();
            assertThat(alice.send(id, "http-bob", "offline").duplicate()).isTrue();
            Delivery first;
            try (var bob = new RelayClient(server(), "http-bob")) {
                first = bob.receive().orElseThrow();
                assertThat(first.messageId()).isEqualTo(id);
                assertThat(bob.receive()).isEmpty();
            }
            try (var bob = new RelayClient(server(), "http-bob")) {
                var retry = bob.receive().orElseThrow();
                assertThat(retry.messageId()).isEqualTo(id);
                assertHttpError(409, "INVALID_RECEIPT", () -> bob.acknowledge(first));
                assertHttpError(404, "MESSAGE_NOT_FOUND", () -> alice.acknowledge(retry));
                bob.acknowledge(retry);
                assertHttpError(404, "MESSAGE_NOT_FOUND", () -> bob.acknowledge(retry));
                assertThat(bob.receive()).isEmpty();
                bob.send(UUID.randomUUID(), "http-alice", "reply");
                var reply = alice.receive().orElseThrow();
                alice.acknowledge(reply);
                assertThat(reply.body()).isEqualTo("reply");
            }
        }
    }

    @Test
    void exposesMailboxAndUtf8LimitsAsHttpErrors() {
        try (var sender = new RelayClient(server(), "limit-sender");
             var receiver = new RelayClient(server(), "limit-receiver")) {
            assertHttpError(413, "MESSAGE_TOO_LARGE", () ->
                    sender.send(UUID.randomUUID(), "limit-receiver", "\u00e9".repeat(17)));
            sender.send(UUID.randomUUID(), "limit-receiver", "first");
            sender.send(UUID.randomUUID(), "limit-receiver", "second");
            assertHttpError(429, "MAILBOX_FULL", () ->
                    sender.send(UUID.randomUUID(), "limit-receiver", "third"));
            receiver.acknowledge(receiver.receive().orElseThrow());
            receiver.acknowledge(receiver.receive().orElseThrow());
        }
    }

    @Test
    void rejectsMalformedJsonMissingHeadersAndUnknownRecipients() {
        var http = http();
        var session = http.put().uri("/api/clients/validation").retrieve().body(Session.class);
        assertThat(session).isNotNull();
        assertHttpStatus(400, () -> http.post().uri("/api/messages")
                .header(CLIENT_ID_HEADER, session.clientId())
                .header(SESSION_ID_HEADER, session.sessionId().toString())
                .contentType(MediaType.APPLICATION_JSON).body("{broken")
                .retrieve().toBodilessEntity());
        assertHttpStatus(400, () -> http.get().uri("/api/messages/next")
                .retrieve().toBodilessEntity());
        assertHttpStatus(405, () -> http.post().uri("/api/clients/validation")
                .retrieve().toBodilessEntity());
        assertHttpStatus(415, () -> http.post().uri("/api/messages")
                .header(CLIENT_ID_HEADER, session.clientId())
                .header(SESSION_ID_HEADER, session.sessionId().toString())
                .contentType(MediaType.TEXT_PLAIN).body("not JSON")
                .retrieve().toBodilessEntity());
        try (var sender = new RelayClient(server(), "unknown-sender")) {
            assertHttpError(404, "UNKNOWN_CLIENT", () -> sender.send(UUID.randomUUID(), "absent", "hello"));
            assertHttpStatus(400, () -> sender.send(UUID.randomUUID(), "validation", " "));
        }
    }

    @Test
    void rejectsOversizedBodiesWithAndWithoutContentLength() throws Exception {
        var payload = "x".repeat(1100).getBytes(StandardCharsets.UTF_8);
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            var publishers = new HttpRequest.BodyPublisher[] {
                    HttpRequest.BodyPublishers.ofByteArray(payload),
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))
            };
            for (var publisher : publishers) {
                var request = HttpRequest.newBuilder(server().resolve("/api/messages"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(publisher).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).contains("REQUEST_TOO_LARGE");
            }
        }
        assertThat(http().put().uri("/api/clients/after-oversized").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
    }

    private RestClient http() {
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone().baseUrl(server().toString()).requestFactory(factory).build();
    }

    private URI server() {
        return URI.create("http://127.0.0.1:" + port);
    }

    
    private void assertHttpStatus(int status,
                                  org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RestClientResponseException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
    }

    private void assertHttpError(int status, String code,
                                 org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RestClientResponseException.class, exception -> {
            assertThat(exception.getStatusCode().value()).isEqualTo(status);
            assertThat(exception.getResponseBodyAsString()).contains("\"code\":\"" + code + "\"");
        });
    }
}
