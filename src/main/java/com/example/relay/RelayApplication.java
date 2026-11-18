package com.example.relay;

import com.example.relay.client.RelayDemo;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class RelayApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--demo")) {
            RelayDemo.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(RelayApplication.class, args);
    }

    @Bean
    Clock relayClock() {
        return Clock.systemUTC();
    }
}
