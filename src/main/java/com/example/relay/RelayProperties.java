package com.example.relay;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("relay")
public record RelayProperties(
        @DefaultValue("100") @Min(1) @Max(1000) int maxClients,
        @DefaultValue("100") @Min(1) @Max(1000) int mailboxCapacity,
        @DefaultValue("4096") @Min(1) @Max(4096) int maxMessageBytes,
        @DefaultValue("16384") @Min(256) @Max(65536) int maxRequestBytes) {

}