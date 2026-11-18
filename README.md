# Java message relay

A Spring Boot 3.5.16 service that relays messages over HTTP. Clients register an identity,
send messages, poll their mailbox, and acknowledge them. The server queues messages for
registered recipients even while they are disconnected. Registrations and mailboxes live
in memory and are cleared on restart.

## Build and test

The build requires JDK 21 and Maven 3.9.16. Run these commands from the directory
containing `pom.xml`.

```text
java -version
mvn -version
mvn clean verify
```

This runs the unit and HTTP integration tests and builds
`target/java-message-relay-1.0.0.jar`. To run the tests on their own, use `mvn test`.
The HTTP tests start an embedded server on an ephemeral port and require no external services.

## Run the server and demo

Start the server in one terminal. It listens on `http://127.0.0.1:8090` by default.

```text
java -jar target/java-message-relay-1.0.0.jar
```

In a second terminal, run the demo against the server URL.

```text
java -jar target/java-message-relay-1.0.0.jar --demo http://127.0.0.1:8090
```

The demo sends a message from Alice to Bob while Bob is disconnected. Bob reconnects,
receives the message, and disconnects before acknowledging it. He reconnects again,
acknowledges the redelivery, and sends a reply that Alice receives and acknowledges.
Run one demo at a time because it uses the fixed IDs `demo-alice` and `demo-bob`.

To use a different port, start the server with a port override.

```text
java -jar target/java-message-relay-1.0.0.jar --server.port=9090
```

In the second terminal, pass the matching URL.

```text
java -jar target/java-message-relay-1.0.0.jar --demo http://127.0.0.1:9090
```

Pass the server URL explicitly because the demo falls back to port 8080 when you omit it.
Stop the server with Ctrl+C. The default graceful shutdown allows up to 10 seconds for
active requests to finish.

## HTTP API

Client IDs match `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Session IDs, message IDs, and
receipts are UUIDs. For send and acknowledge requests, set `Content-Type: application/json`.

| Method and path | Required headers | Request body | Success |
| --- | --- | --- | --- |
| `PUT /api/clients/{clientId}` | None | None | `200` with `clientId` and a new `sessionId` |
| `DELETE /api/clients/{clientId}/session` | `X-Session-Id` | None | `204` |
| `POST /api/messages` | `X-Client-Id`, `X-Session-Id` | `messageId`, `recipient`, `body` | `202` with `messageId` and `duplicate` |
| `GET /api/messages/next` | `X-Client-Id`, `X-Session-Id` | None | `200` with `messageId`, `sender`, `recipient`, `body`, `acceptedAt`, and `receipt`, or `204` |
| `POST /api/messages/{messageId}/ack` | `X-Client-Id`, `X-Session-Id` | `receipt` | `204` |

- Register the recipient at least once before sending to them. A `202 Accepted` response
  confirms the message is queued, or already pending if `duplicate` is true.
- Polling returns immediately. Each mailbox delivers in the order messages were accepted,
  with one message in flight at a time. A `204 No Content` means the mailbox is empty or
  its first message is awaiting acknowledgement.
- Acknowledge with the current recipient session and the receipt from the latest delivery.
  The message stays queued until a valid acknowledgement removes it. To redeliver an
  unacknowledged message, register the same client ID again and poll for a fresh receipt.
  Re-registering replaces the previous session without authentication.
- You can retry an uncertain send with the same recipient, message ID, sender identity,
  and body. While the message is pending, the relay returns `202` with `duplicate: true`.
  Reusing that ID in the same mailbox with a different sender or body returns `409`.
  After acknowledgement, the relay can accept the ID as a new message.

Relay errors use a JSON response with `code` and `message` fields. For example, a full
mailbox returns `429 MAILBOX_FULL`, a stale session returns `409 STALE_SESSION`, and an
unknown recipient returns `404 UNKNOWN_CLIENT`. Spring handles malformed JSON and
missing headers with `400 Bad Request`.

## Configuration


| Setting | Default | Purpose |
| --- | --- | --- |
| `relay.max-clients` | 100 | Retained identities, including disconnected clients |
| `relay.mailbox-capacity` | 100 | Pending messages per recipient, including the in-flight head |
| `relay.max-message-bytes` | 4096 | Message body limit in UTF-8 bytes |
| `relay.max-request-bytes` | 16384 | HTTP request body limit in bytes, including JSON and chunked uploads |
| `server.address` | 127.0.0.1 | Bind address |
| `server.port` | 8090 | Listen port |
| `server.max-http-request-header-size` | 8 KiB | HTTP request header limit |
| `server.tomcat.max-connections` | 128 | Accepted HTTP connections |
| `server.tomcat.threads.max` | 32 | Request worker threads |
| `server.tomcat.accept-count` | 32 | Connection backlog when connection capacity is reached |
| `server.tomcat.connection-timeout` | 5s | Socket read timeout for request input |
| `server.tomcat.keep-alive-timeout` | 5s | Idle keep-alive timeout |

The HTTP API requires a non-blank message body and also applies a maximum string length of
4096. The included Java client uses Spring's `RestClient` with the JDK HTTP client,
a 2-second connection timeout, and a 5-second read timeout.

See [APPROACH.md](APPROACH.md) for the design rationale, concurrency model, and test scope.
