package com.printflow.performanceclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class PerformanceClientApplication {

    @Value("${printflow.server.base-url:http://localhost:8081}")
    private String serverBaseUrl;

    public static void main(String[] args) {
        SpringApplication.run(PerformanceClientApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public CommandLineRunner healthCheck(RestTemplate restTemplate) {
        return args -> {
            String healthUrl = serverBaseUrl + "/actuator/health";
            long start = System.nanoTime();

            try {
                String response = restTemplate.getForObject(healthUrl, String.class);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                System.out.println("Health-Check to " + healthUrl);
                System.out.println("Answer: " + response);
                System.out.println("Runtime: " + elapsedMs + " ms");
            } catch (Exception ex) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                System.out.println("Health-Check failed to " + healthUrl + " after " + elapsedMs + " ms");
                System.out.println("Error: " + ex.getMessage());
            }
        };
    }
}
