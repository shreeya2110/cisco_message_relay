package com.example.relay;

public class RelayException extends RuntimeException {

    public enum Reason {
        INVALID_INPUT, UNKNOWN_CLIENT, STALE_SESSION, CLIENT_LIMIT, MAILBOX_FULL,
        MESSAGE_TOO_LARGE, MESSAGE_CONFLICT, MESSAGE_NOT_FOUND, INVALID_RECEIPT
    }

    private final Reason reason;

    public RelayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
