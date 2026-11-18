package com.example.relay.client;

import static com.example.relay.RelayProtocol.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class RelayClient implements AutoCloseable {

    private final HttpClient httpClient;
    private final RestClient restClient;
    private final Session session;

    public RelayClient(URI server, String clientId) {
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        var builder = RestClient.builder().baseUrl(server.toString()).requestFactory(factory);
        try {
            session = builder.build().put().uri("/api/clients/{clientId}", clientId)
                    .retrieve().body(Session.class);
            if (session == null) {
                throw new IllegalStateException("Server returned no registration session");
            }
            restClient = builder.defaultHeader(CLIENT_ID_HEADER, session.clientId())
                    .defaultHeader(SESSION_ID_HEADER, session.sessionId().toString()).build();
        } catch (RuntimeException exception) {
            httpClient.close();
            throw exception;
        }
    }

    public SendResult send(UUID messageId, String recipient, String body) {
        return restClient.post().uri("/api/messages").contentType(MediaType.APPLICATION_JSON)
                .body(new SendRequest(messageId, recipient, body))
                .retrieve().body(SendResult.class);
    }

    public Optional<Delivery> receive() {
        return Optional.ofNullable(restClient.get().uri("/api/messages/next")
                .retrieve().body(Delivery.class));
    }

    public void acknowledge(Delivery delivery) {
        restClient.post().uri("/api/messages/{messageId}/ack", delivery.messageId())
                .contentType(MediaType.APPLICATION_JSON).body(new AckRequest(delivery.receipt()))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void close() {
        try {
            restClient.delete().uri("/api/clients/{clientId}/session", session.clientId())
                    .retrieve().toBodilessEntity();
        } finally {
            httpClient.close();
        }
    }
}
