package com.example.relay.client;

import java.net.URI;
import java.util.UUID;

public final class RelayDemo {

    private RelayDemo() {
    }

    public static void main(String[] args) {
        var server = URI.create(args.length == 0 ? "http://127.0.0.1:8080" : args[0]);
        try (var alice = new RelayClient(server, "demo-alice")) {
            try (var bob = new RelayClient(server, "demo-bob")) {
                System.out.println("Alice and Bob registered; Bob is disconnecting.");
            }
            var id = UUID.randomUUID();
            alice.send(id, "demo-bob", "Hello while you were offline");
            System.out.println("Send accepted for offline Bob: " + id);

            try (var bob = new RelayClient(server, "demo-bob")) {
                var delivery = bob.receive().orElseThrow();
                System.out.println("Bob received: " + delivery.body() + "; disconnecting without ACK.");
            }
            try (var bob = new RelayClient(server, "demo-bob")) {
                var redelivery = bob.receive().orElseThrow();
                if (!redelivery.messageId().equals(id)) {
                    throw new IllegalStateException("Expected the unacknowledged message");
                }
                bob.acknowledge(redelivery);
                System.out.println("Bob reconnected and acknowledged the redelivery: " + id);

                bob.send(UUID.randomUUID(), "demo-alice", "Message received, thanks");
                var reply = alice.receive().orElseThrow();
                alice.acknowledge(reply);
                System.out.println("Alice received and acknowledged: " + reply.body());
            }
        }
    }
}
