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

    public static void main(String[] args) {
        SpringApplication.run(PrinterProcessApplication.class, args);
    }

    @Bean
    CommandLineRunner startup() {
        return args -> {
            System.out.println("Printer Process Started");
            System.out.println("Printer ID: " + printerId + " Server: " + serverHost + ":" + serverPort);

            TcpPrinterClient client = new TcpPrinterClient(serverHost, serverPort, printerId, "Printer " + printerId);
            client.connect();
        };
    }
}
