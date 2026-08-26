package com.printflow.server.dispatcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DispatcherConfig {

    @Bean
    public Dispatcher dispatcher(@Value("${printflow.dispatch.strategy:round-robin}") String strategy,
                                 com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry) {
        return switch (strategy.toLowerCase()) {
            case "least-loaded", "leastloaded", "least-loaded-first" -> new Dispatcher(new LeastLoadedStrategy(), connectionRegistry);
            default -> new Dispatcher(new RoundRobinStrategy(), connectionRegistry);
        };
    }
}
