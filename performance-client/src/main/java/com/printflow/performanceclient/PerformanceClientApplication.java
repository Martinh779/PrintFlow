package com.printflow.performanceclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class PerformanceClientApplication {

    @Value("${printflow.server.base-url:http://localhost:8081}")
    private String serverBaseUrl;

    public static void main(String[] args) {
        boolean interactive = Arrays.asList(args).contains("--interactive") ||
                Boolean.parseBoolean(System.getProperty("printflow.client.interactive", "false"));

        SpringApplication app = new SpringApplication(PerformanceClientApplication.class);
        if (interactive) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
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

    @Bean
    public CommandLineRunner interactiveCli(RestTemplate restTemplate) {
        return args -> {
            boolean interactive = Arrays.asList(args).contains("--interactive") ||
                    Boolean.parseBoolean(System.getProperty("printflow.client.interactive", "false"));
            if (!interactive) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("PrintFlow Interactive Client");
            String menu = "\nChoose action:\n1) Create Job\n2) Get Job by ID\n3) List Jobs (optionally by user)\n4) Cancel Job\n5) Get Result\n6) Exit\n> ";

            while (true) {
                System.out.print(menu);
                String line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                try {
                    switch (line) {
                        case "1":
                            createJobInteractive(restTemplate, reader);
                            break;
                        case "2":
                            System.out.print("Job ID: ");
                            String id = reader.readLine().trim();
                            String getUrl = serverBaseUrl + "/api/jobs/" + id;
                            System.out.println("Response: " + restTemplate.getForObject(getUrl, String.class));
                            break;
                        case "3":
                            System.out.print("User ID (leave empty to list all): ");
                            String user = reader.readLine().trim();
                            String listUrl = serverBaseUrl + "/api/jobs" + (user.isEmpty() ? "" : "?userId=" + user);
                            System.out.println("Response: " + restTemplate.getForObject(listUrl, String.class));
                            break;
                        case "4":
                            System.out.print("Job ID to cancel: ");
                            String cancelId = reader.readLine().trim();
                            String cancelUrl = serverBaseUrl + "/api/jobs/" + cancelId;
                            ResponseEntity<String> resp = restTemplate.exchange(cancelUrl, HttpMethod.DELETE, null, String.class);
                            System.out.println("Cancel response: " + resp.getBody());
                            break;
                        case "5":
                            System.out.print("Job ID for result: ");
                            String resId = reader.readLine().trim();
                            String resUrl = serverBaseUrl + "/api/jobs/" + resId + "/result";
                            System.out.println("Response: " + restTemplate.getForObject(resUrl, String.class));
                            break;
                        case "6":
                            System.out.println("Exiting interactive client.");
                            return;
                        default:
                            System.out.println("Unknown option");
                    }
                } catch (Exception ex) {
                    System.out.println("Error during request: " + ex.getMessage());
                }
            }
        };
    }

    private void createJobInteractive(RestTemplate restTemplate, BufferedReader reader) throws Exception {
        System.out.print("fileReference: ");
        String fileRef = reader.readLine().trim();
        System.out.print("priority (integer): ");
        String priorityStr = reader.readLine().trim();
        int priority = Integer.parseInt(priorityStr);
        System.out.print("userId (optional): ");
        String userId = reader.readLine().trim();

        System.out.println("Now profile information (press enter to skip optional fields)");
        System.out.print("profile id: ");
        String pid = reader.readLine().trim();
        System.out.print("profile name: ");
        String pname = reader.readLine().trim();
        System.out.print("paper size: ");
        String paper = reader.readLine().trim();
        System.out.print("color mode: ");
        String color = reader.readLine().trim();
        System.out.print("duplexSupported (true/false): ");
        String duplex = reader.readLine().trim();
        boolean duplexSupported = "true".equalsIgnoreCase(duplex);

        Map<String, Object> profile = new HashMap<>();
        if (!pid.isEmpty()) profile.put("id", pid);
        if (!pname.isEmpty()) profile.put("name", pname);
        if (!paper.isEmpty()) profile.put("paperSize", paper);
        if (!color.isEmpty()) profile.put("colorMode", color);
        profile.put("duplexSupported", duplexSupported);

        Map<String, Object> request = new HashMap<>();
        request.put("fileReference", fileRef);
        request.put("profile", profile);
        request.put("priority", priority);
        if (!userId.isEmpty()) request.put("userId", userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        String url = serverBaseUrl + "/api/jobs";
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        System.out.println("Create response: " + response.getStatusCode() + " - " + response.getBody());
    }
}
