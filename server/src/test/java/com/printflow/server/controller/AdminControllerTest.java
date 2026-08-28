package com.printflow.server.controller;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.socket.PrinterSimulatorManager;
import com.printflow.server.socket.TcpPrinterServer;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    @Test
    void listPrintersIncludesSimulatorState() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        PrintJobRepository repository = mock(PrintJobRepository.class);
        TcpPrinterServer tcpPrinterServer = mock(TcpPrinterServer.class);
        PrinterSimulatorManager simulatorManager = mock(PrinterSimulatorManager.class);

        Dispatcher.PrinterRegistration printer = new Dispatcher.PrinterRegistration(
                "printer-1",
                "Printer One",
                "localhost",
                50000,
                true,
                List.of(new PrinterProfile("profile-1", "A4 Color", "A4", "COLOR", false))
        );

        when(dispatcher.getRegisteredPrinters()).thenReturn(List.of(printer));
        when(simulatorManager.isRunning("printer-1")).thenReturn(true);

        AdminController controller = new AdminController(dispatcher, repository, tcpPrinterServer, simulatorManager);
        ResponseEntity<List<Map<String, Object>>> response = controller.listPrinters();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("printer-1", response.getBody().getFirst().get("id"));
        assertEquals("profile-1", ((List<Map<String, Object>>) response.getBody().getFirst().get("supportedProfiles")).getFirst().get("id"));
        assertEquals(Boolean.TRUE, response.getBody().getFirst().get("simulatorRunning"));
    }

    @Test
    void createPrinterRegistersPrinterWithProfiles() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        PrintJobRepository repository = mock(PrintJobRepository.class);
        TcpPrinterServer tcpPrinterServer = mock(TcpPrinterServer.class);
        PrinterSimulatorManager simulatorManager = mock(PrinterSimulatorManager.class);

        AdminController controller = new AdminController(dispatcher, repository, tcpPrinterServer, simulatorManager);
        AdminController.CreatePrinterRequest request = new AdminController.CreatePrinterRequest();
        request.id = "printer-77";
        request.name = "Printer 77";
        request.online = true;
        request.supportedProfiles = List.of(Map.of("id", "profile-9", "name", "Poster"));

        ResponseEntity<?> response = controller.createPrinter(request);

        ArgumentCaptor<Dispatcher.PrinterRegistration> captor = ArgumentCaptor.forClass(Dispatcher.PrinterRegistration.class);
        verify(dispatcher).registerPrinter(captor.capture());

        assertEquals(201, response.getStatusCode().value());
        assertEquals("printer-77", captor.getValue().getId());
        assertEquals(1, captor.getValue().getSupportedProfiles().size());
        assertEquals("profile-9", captor.getValue().getSupportedProfiles().getFirst().getId());
    }

    @Test
    void connectPrinterUsesSuppliedHostAndPort() throws Exception {
        Dispatcher dispatcher = mock(Dispatcher.class);
        PrintJobRepository repository = mock(PrintJobRepository.class);
        TcpPrinterServer tcpPrinterServer = mock(TcpPrinterServer.class);
        PrinterSimulatorManager simulatorManager = mock(PrinterSimulatorManager.class);

        Dispatcher.PrinterRegistration printer = new Dispatcher.PrinterRegistration(
                "printer-2",
                "Printer Two",
                "localhost",
                0,
                true,
                List.of()
        );

        when(dispatcher.getRegisteredPrinters()).thenReturn(List.of(printer));
        when(tcpPrinterServer.connectToPrinter(eq("printer-2"), eq("Printer Two"), eq("127.0.0.1"), eq(60000), eq(true), anyList()))
                .thenReturn(true);

        AdminController controller = new AdminController(dispatcher, repository, tcpPrinterServer, simulatorManager);
        ResponseEntity<?> response = controller.connectPrinter("printer-2", Map.of(
                "host", "127.0.0.1",
                "port", 60000,
                "name", "Printer Two"
        ));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) response.getBody()).get("connected"));
        verify(tcpPrinterServer).connectToPrinter(eq("printer-2"), eq("Printer Two"), eq("127.0.0.1"), eq(60000), eq(true), anyList());
    }

    @Test
    void getDispatchPolicyReturnsCurrentAndAvailableStrategies() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        PrintJobRepository repository = mock(PrintJobRepository.class);
        TcpPrinterServer tcpPrinterServer = mock(TcpPrinterServer.class);
        PrinterSimulatorManager simulatorManager = mock(PrinterSimulatorManager.class);

        when(dispatcher.getDispatchStrategy()).thenReturn("least-loaded");
        when(dispatcher.getDefaultDispatchStrategy()).thenReturn("round-robin");
        when(dispatcher.getAvailableDispatchStrategies()).thenReturn(List.of(
                Map.of("key", "round-robin", "label", "Round Robin"),
                Map.of("key", "least-loaded", "label", "Least Loaded"),
                Map.of("key", "priority-aware", "label", "Priority Aware")
        ));

        AdminController controller = new AdminController(dispatcher, repository, tcpPrinterServer, simulatorManager);
        ResponseEntity<Map<String, Object>> response = controller.getDispatchPolicy();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("least-loaded", response.getBody().get("strategy"));
        assertEquals("round-robin", response.getBody().get("defaultStrategy"));
        assertNotNull(response.getBody().get("availableStrategies"));
        assertNotNull(response.getBody().get("printerPolicy"));
    }

    @Test
    void updateDispatchPolicyAppliesRequestedStrategy() {
        Dispatcher dispatcher = mock(Dispatcher.class);
        PrintJobRepository repository = mock(PrintJobRepository.class);
        TcpPrinterServer tcpPrinterServer = mock(TcpPrinterServer.class);
        PrinterSimulatorManager simulatorManager = mock(PrinterSimulatorManager.class);

        when(dispatcher.getDispatchStrategy()).thenReturn("priority-aware");
        when(dispatcher.getDefaultDispatchStrategy()).thenReturn("round-robin");
        when(dispatcher.getAvailableDispatchStrategies()).thenReturn(List.of(
                Map.of("key", "round-robin", "label", "Round Robin"),
                Map.of("key", "least-loaded", "label", "Least Loaded"),
                Map.of("key", "priority-aware", "label", "Priority Aware")
        ));

        AdminController controller = new AdminController(dispatcher, repository, tcpPrinterServer, simulatorManager);
        AdminController.UpdateDispatchPolicyRequest request = new AdminController.UpdateDispatchPolicyRequest();
        request.strategy = "priority-aware";

        ResponseEntity<?> response = controller.updateDispatchPolicy(request);

        assertEquals(200, response.getStatusCode().value());
        verify(dispatcher).setDispatchStrategy("priority-aware");
    }
}
