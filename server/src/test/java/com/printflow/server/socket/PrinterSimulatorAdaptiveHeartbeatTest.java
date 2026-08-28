package com.printflow.server.socket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(properties = {
        "printflow.socket.port=51025",
        "printflow.socket.read-timeout-ms=1000",
        "printflow.socket.heartbeat-timeout-ms=1000",
        "printflow.socket.heartbeat-check-interval-ms=100"
})
class PrinterSimulatorAdaptiveHeartbeatTest {

    @Autowired
    private PrinterSimulatorManager simulatorManager;

    @Autowired
    private PrinterConnectionRegistry connectionRegistry;

    @Test
    void simulatorAdaptsHeartbeatIntervalToLowSocketTimeout() throws Exception {
        String printerId = "sim-adaptive-heartbeat-printer";
        assertTrue(simulatorManager.startSimulator(printerId, "Sim Adaptive Heartbeat Printer"));
        try {
            awaitCondition(() -> connectionRegistry.hasConnection(printerId), Duration.ofSeconds(2),
                    "Simulator did not establish a TCP connection in time");
            Thread.sleep(2200);
            assertTrue(connectionRegistry.hasConnection(printerId),
                    "Simulator should stay connected even with low socket timeout");
        } finally {
            assertTrue(simulatorManager.stopSimulator(printerId));
            awaitCondition(() -> !connectionRegistry.hasConnection(printerId), Duration.ofSeconds(2),
                    "Simulator connection was not removed after stop");
            assertFalse(simulatorManager.isRunning(printerId));
        }
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }
}
