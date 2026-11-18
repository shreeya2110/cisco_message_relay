package com.example.relay;

import com.example.relay.RelayProtocol.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class RelayErrorHandler {

    @ExceptionHandler(RelayException.class)
    ResponseEntity<ApiError> handleRelayError(RelayException exception) {
        var status = switch (exception.reason()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNKNOWN_CLIENT, MESSAGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STALE_SESSION, MESSAGE_CONFLICT, INVALID_RECEIPT -> HttpStatus.CONFLICT;
            case MAILBOX_FULL, CLIENT_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case MESSAGE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(exception.reason().name(), exception.getMessage()));
    }

}
