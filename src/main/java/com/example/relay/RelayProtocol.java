package com.example.relay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class RelayProtocol {

    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String SESSION_ID_HEADER = "X-Session-Id";
    public static final String CLIENT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    private RelayProtocol() {
    }

    public record Session(String clientId, UUID sessionId) {
    }

    public record SendRequest(
            @NotNull UUID messageId,
            @NotNull @Pattern(regexp = CLIENT_ID_PATTERN) String recipient,
            @NotBlank @Size(max = 4096) String body) {
    }

    public record SendResult(UUID messageId, boolean duplicate) {
    }

    public record Delivery(UUID messageId, String sender, String recipient, String body,
                           Instant acceptedAt, UUID receipt) {
    }

    public record AckRequest(@NotNull UUID receipt) {
    }

    public record ApiError(String code, String message) {
    }
}
