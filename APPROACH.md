# Approach

## My approach

I wrote this assessment approach after reviewing the requirements and the available
alternatives. I chose an HTTP REST protocol, implemented the relay in a single Spring
Boot process, and verified the implementation through unit and real-HTTP tests. The main
design goals were predictable delivery, explicit acknowledgement, bounded memory use,
safe concurrency, and an implementation that I could explain and test end to end.

I used Spring Boot and embedded Tomcat for HTTP and process infrastructure. I tested the
ordinary send-and-receive flow as well as reconnects, duplicate sends, stale receipts,
redelivery, and capacity limits.

## Scope and acceptance criteria

The exercise allows HTTP and frameworks provided the relay owns its behaviour. Spring
Boot handles HTTP routing, JSON, validation, configuration, and process lifecycle.
`RelayService` implements registration, message retention, delivery, and acknowledgement.

| Requirement | Implementation and verification |
| --- | --- |
| Multiple registered clients and sends in either direction | Service and HTTP tests exchange messages between two registered clients |
| Explicit send acceptance or rejection | HTTP tests check `202` with a message ID and `4xx` responses for rejected requests |
| Offline retention and reconnect | Service and HTTP tests verify that the mailbox outlives the session |
| Explicit recipient ACK before removal | Tests reject ACKs from another mailbox or with a stale receipt |
| Redelivery without ACK | Service and HTTP tests verify redelivery on reconnect. A service test advances the clock and checks that the existing session still sees no delivery |
| Bounded state and input | Tests cover identity and mailbox capacity, UTF-8 message size, and request size with and without a declared content length |
| Correct concurrent state | A service test checks that concurrent sends respect mailbox capacity and preserve accepted messages |
| Per-recipient FIFO | A service test verifies insertion order across reconnects with one message in flight |

## HTTP protocol

The Java client uses HTTP/1.1, with HTTP providing message framing and JSON carrying
structured request and response bodies. Client IDs match
`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Message IDs, session IDs, and receipts are UUIDs.
Send and ACK requests use `Content-Type: application/json`. The send body must be non-blank
and fit both the configured UTF-8 byte limit and the validation limit of 4096 UTF-16 code units.

| Operation | Request | Success |
| --- | --- | --- |
| Register or reconnect | `PUT /api/clients/{clientId}`, no body | `200 {"clientId":"alice","sessionId":"<uuid>"}` |
| Disconnect | `DELETE /api/clients/{clientId}/session`, `X-Session-Id` | `204` |
| Send | `POST /api/messages`, session headers and send JSON | `202 {"messageId":"<uuid>","duplicate":false}` |
| Receive | `GET /api/messages/next`, session headers | `200` delivery JSON, or `204` |
| Acknowledge | `POST /api/messages/{messageId}/ack`, session headers, `{"receipt":"<uuid>"}` | `204` |

Session headers are `X-Client-Id: alice` and `X-Session-Id: <uuid>`. The disconnect request
supplies the client ID in the URL and the session ID in its header. Anyone who can reach
the relay can register a client ID and replace its current session. Registering the same
ID again after a lost registration response preserves the queued messages.

An example send request for Bob follows.

```json
{
  "messageId": "21332773-866a-45ec-8a3c-7e23e68e6c42",
  "recipient": "bob",
  "body": "hello"
}
```

Bob receives the message with a receipt and the time the relay accepted it.

```json
{
  "messageId": "21332773-866a-45ec-8a3c-7e23e68e6c42",
  "sender": "alice",
  "recipient": "bob",
  "body": "hello",
  "acceptedAt": "2026-01-01T00:00:00Z",
  "receipt": "de1809bc-4938-49ef-a58b-09b03e3d74c9"
}
```

A `202` confirms that the message was queued or that a matching message is already pending.
Sending requires a current sender session and a recipient who has registered at least once.
The recipient may be disconnected. After a lost send response, the caller can explicitly
retry with the same message ID, recipient, sender identity, and body.

Relay errors have the shape `{"code":"MAILBOX_FULL","message":"Recipient mailbox is full"}`.

| HTTP status | Relay error codes |
| --- | --- |
| `400` | `INVALID_INPUT` |
| `404` | `UNKNOWN_CLIENT`, `MESSAGE_NOT_FOUND` |
| `409` | `STALE_SESSION`, `MESSAGE_CONFLICT`, `INVALID_RECEIPT` |
| `413` | `MESSAGE_TOO_LARGE`, `REQUEST_TOO_LARGE` |
| `429` | `CLIENT_LIMIT`, `MAILBOX_FULL` |

Spring uses its own error response for malformed JSON, missing required headers, and
request validation failures (`400`), unsupported methods (`405`), and unsupported media types
(`415`). Tomcat may reject invalid framing or excessive headers before the request reaches
the application.

## Delivery and lifecycle

Each mailbox has a FIFO map of pending messages. Polling returns the head and assigns a
receipt while keeping the message queued. Polls return immediately with `204` if the
mailbox is empty or the head is awaiting ACK. The client chooses its polling interval
and can back off after empty polls or capacity errors.

ACK requires the current recipient session and the most recent delivery receipt.
An ACK returns `404 MESSAGE_NOT_FOUND` if the ID is absent from the caller's mailbox,
including after successful removal. If the ID is pending but the receipt does not match,
it returns `409 INVALID_RECEIPT`. After a lost ACK response, a retry in the same recipient
session can treat `MESSAGE_NOT_FOUND` as confirmation that the message is absent, provided
the original ID was reserved for that send.

Disconnect clears the session and delivery receipts, keeping every message. Reconnect
replaces the session and makes the head immediately available. Requests using the
previous session ID receive `409 STALE_SESSION`. The logical session survives a TCP
connection closing. After a crash or a lost delivery response, the client can register
the same ID again and poll for a fresh receipt.

The relay supports at-least-once delivery while the process stays alive and the recipient
reconnects and polls after an interrupted delivery. Clients must handle possible duplicate
processing because a redelivery can overlap work still running in the previous client.

Pending duplicate sends are keyed by `(recipient, messageId)`. The same sender and
body return `202` with `duplicate: true`, even when the mailbox is full. A different
sender or body using that key gets `409`. Once ACK removes a message, its ID may be
accepted again. Generate a new UUID for each new send and reserve reuse for retries of
the same send.

FIFO follows the order in which sends are accepted under the state lock. An
unacknowledged head blocks later messages, limiting throughput for that mailbox
and preserving order across reconnects.

## State, concurrency, and bounds

One service monitor protects the registry and mailboxes. Validation of relay state
and the resulting mutation happen under the same lock. This enforces mailbox capacity
under concurrent sends and serializes ACKs and reconnects. Socket reads and JSON
processing happen outside the lock. Send, poll, and ACK use map operations that take
constant time on average with respect to mailbox size. Reconnect and disconnect scan
the bounded mailbox to clear delivery receipts. I chose one lock to keep the state
transitions atomic without coordinating locks across mailboxes.

The default retained payload budget is 100 identities × 100 messages × 4096 UTF-8 bytes,
about 39 MiB of message bodies measured in UTF-8. The JVM stores those bodies as Java
strings and also holds object metadata, sessions, and HTTP buffers. Registered identities
occupy slots until the server stops. A known identity can reconnect even when the
registry is full.

The request filter rejects a declared length above the configured limit. Otherwise,
it reads up to the limit plus one byte before JSON parsing, which lets it detect
oversized chunked requests as well. Tomcat limits connections, worker threads,
connection backlog, request headers, the amount of discarded request data it reads,
idle keep-alive time, requests per keep-alive connection, and socket read waits.
Slow clients can still fill the available connections or workers. The socket read
timeout bounds inactivity, so an upload that keeps sending data can take longer.

Spring manages Tomcat and gives active HTTP requests up to 10 seconds to finish during
graceful shutdown by default. The demo closes each logical session and its underlying
JDK HTTP client. Registrations and messages are lost whenever the process stops.

## Tests and verification

`RelayServiceTest` checks both directions, offline retention, FIFO across reconnects,
reconnect-only redelivery, receipt/session ownership, duplicate semantics,
registry and byte limits, and concurrent capacity enforcement. `RelayHttpTest` starts
the real server on an ephemeral port and drives it with the supplied REST client and
JDK HTTP client, including malformed JSON and oversized fixed/chunked requests.

The tests use bounded waits and JUnit timeouts. The suite focuses on protocol behaviour
and selected concurrency checks. Throughput, slow-client saturation, OS connection
backlog behaviour, process termination under load, and heap usage would require
separate measurements.

Run `mvn clean verify` with JDK 21 and Maven 3.9.16, as required by the Maven Enforcer rules.
For CI, the same command can run with `--batch-mode`. The build produces
`target/java-message-relay-1.0.0.jar` for both the server and the separate client demo.
[Build and run instructions](README.md)

## Trade-offs and next steps

I would extend the central exception handler so relay errors, malformed requests,
and validation failures use the same JSON format with `code` and `message` fields.
Each error would keep its appropriate HTTP status. Unexpected failures would return
a generic `500` response and log the details on the server.

I would add timed redelivery within the same session. After each delivery attempt,
the relay would use its clock to set the earliest retry time based on the configured
delay, the attempt count, and a small random variation to spread out retries. If the
message is still unacknowledged, the first poll after that time would return the same message ID with a fresh receipt.

I would add `Cache-Control: no-store` to both the `200 OK` and `204 No Content` polling responses. This keeps a cache from reusing a previous delivery or an empty response after the mailbox has changed.

I would have the client increase the polling interval after consecutive `204`
responses, up to a configurable maximum, and reset it to the shortest interval when
a message arrives. This reduces requests to idle mailboxes. A new message may wait
until the next poll.

For the retry configuration, I would add a compact constructor to the `RelayProperties`
record. It would require a retry delay between 1 millisecond and 5 minutes, inclusive.
A missing delay or a value outside that range would throw an `IllegalArgumentException`
during construction, preventing Spring from creating the configuration object and
starting the relay with that setting.


## AI use and my responsibility

I designed the relay and made the decisions about how clients register, how messages
stay queued while a recipient is offline, and when a message can be delivered again.
I used AI to help write boilerplate code, suggest improvements, and refine parts of the code and documentation. I reviewed those suggestions and decided which to use.

I ran the Maven build and test suite, then used the HTTP demo to check offline delivery, redelivery after reconnecting without an acknowledgement, and the reply from Bob to Alice.

