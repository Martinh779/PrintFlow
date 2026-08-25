package com.printflow.printerprocess;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PrinterProcessApplication {

    @Value("${printer.id:printer-001}")
    private String printerId;

    @Value("${server.host:localhost}")
    private String serverHost;

    @Value("${server.port:50000}")
    private int serverPort;

    @Value("${printer.capacity:2}")
    private int printerCapacity;

    @Value("${printer.supportedProfiles:}")
    private String supportedProfilesCsv;

    public static void main(String[] args) {
        SpringApplication.run(PrinterProcessApplication.class, args);
    }

    @Bean
    CommandLineRunner startup() {
        return args -> {
            System.out.println("Printer Process Started");
            System.out.println("Printer ID: " + printerId + " Server: " + serverHost + ":" + serverPort + " capacity=" + printerCapacity);

            // parse supported profiles csv into simple PrinterProfile objects (only id and name)
            java.util.List<com.printflow.sharedmodel.model.PrinterProfile> profiles = new java.util.ArrayList<>();
            if (supportedProfilesCsv != null && !supportedProfilesCsv.isBlank()) {
                for (String id : supportedProfilesCsv.split(",")) {
                    id = id.trim();
                    if (!id.isEmpty()) {
                        com.printflow.sharedmodel.model.PrinterProfile p = new com.printflow.sharedmodel.model.PrinterProfile();
                        p.setId(id);
                        p.setName(id);
                        profiles.add(p);
                    }
                }
            }

            TcpPrinterClient client = new TcpPrinterClient(serverHost, serverPort, printerId, "Printer " + printerId, printerCapacity, profiles);
            client.connect();
        };
    }
}
