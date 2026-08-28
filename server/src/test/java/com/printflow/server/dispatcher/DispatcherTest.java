package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {

    @Test
    void roundRobinStrategySelectsPrintersInTurn() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        List<Dispatcher.PrinterRegistration> printers = List.of(
                new Dispatcher.PrinterRegistration("printer-1", "Printer 1", true),
                new Dispatcher.PrinterRegistration("printer-2", "Printer 2", true)
        );

        PrintJob job1 = new PrintJob("job-1", "file-1.pdf", new PrinterProfile("profile-1", "Office", "A4", "COLOR", false), 1);
        PrintJob job2 = new PrintJob("job-2", "file-2.pdf", new PrinterProfile("profile-2", "Office", "A4", "BW", true), 1);

        assertEquals("printer-1", strategy.select(printers, job1).orElseThrow().getId());
        assertEquals("printer-2", strategy.select(printers, job2).orElseThrow().getId());
    }

    @Test
    void dispatcherAssignsEveryQueuedJobExactlyOnce() {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());

        dispatcher.registerPrinter("printer-1", "Printer 1", true);
        dispatcher.registerPrinter("printer-2", "Printer 2", true);

        List<PrintJob> jobs = IntStream.range(0, 100)
                .mapToObj(i -> new PrintJob(
                        "job-" + i,
                        "file-" + i + ".pdf",
                        new PrinterProfile("profile-" + i, "Office", "A4", "COLOR", false),
                        10
                ))
                .toList();

        jobs.forEach(dispatcher::enqueue);

        List<PrinterAssignment> assignments = new ArrayList<>();
        while (!dispatcher.getQueueSnapshot().isEmpty()) {
            dispatcher.dispatchNext().ifPresent(assignments::add);
        }

        assertEquals(100, assignments.size());
        assertEquals(100, assignments.stream().map(a -> a.job().getId()).distinct().count());
        assertTrue(assignments.stream().allMatch(a -> a.job().getStatus() == PrintJobStatus.ASSIGNED));
    }

    @Test
    void concurrentDispatchDoesNotDuplicateAssignments() throws InterruptedException {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());
        dispatcher.registerPrinter("printer-1", "Printer 1", true);
        dispatcher.registerPrinter("printer-2", "Printer 2", true);

        List<PrintJob> jobs = IntStream.range(0, 100)
                .mapToObj(i -> new PrintJob(
                        "job-" + i,
                        "file-" + i + ".pdf",
                        new PrinterProfile("profile-" + i, "Office", "A4", "COLOR", false),
                        8
                ))
                .toList();

        jobs.parallelStream().forEach(dispatcher::enqueue);

        Set<String> assignedIds = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    var assignment = dispatcher.dispatchNext();
                    if (assignment.isEmpty()) {
                        break;
                    }
                    assignedIds.add(assignment.get().job().getId());
                }
            });
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        assertEquals(100, assignedIds.size());
        assertEquals(0, dispatcher.queueSize());
    }

    @Test
    void dispatcherCanSwitchStrategiesAtRuntime() {
        Dispatcher dispatcher = new Dispatcher("round-robin", null);
        assertEquals("round-robin", dispatcher.getDispatchStrategy());
        assertEquals("round-robin", dispatcher.getDefaultDispatchStrategy());

        dispatcher.setDispatchStrategy("least-loaded");
        assertEquals("least-loaded", dispatcher.getDispatchStrategy());

        dispatcher.setDispatchStrategy("priority-aware");
        assertEquals("priority-aware", dispatcher.getDispatchStrategy());
    }

    @Test
    void priorityAwareStrategyPrefersProfileMatchForHighPriorityJob() {
        PriorityAwareStrategy strategy = new PriorityAwareStrategy();

        Dispatcher.PrinterRegistration wildcardPrinter = new Dispatcher.PrinterRegistration(
                "printer-any",
                "Printer Any",
                "localhost",
                0,
                true,
                List.of()
        );
        wildcardPrinter.incrementAssignments();
        wildcardPrinter.incrementAssignments();

        Dispatcher.PrinterRegistration exactMatchPrinter = new Dispatcher.PrinterRegistration(
                "printer-exact",
                "Printer Exact",
                "localhost",
                0,
                true,
                List.of(new PrinterProfile("profile-a4", "A4", "A4", "BW", true))
        );
        exactMatchPrinter.incrementAssignments();

        PrintJob urgentJob = new PrintJob(
                "job-prio",
                "urgent.pdf",
                new PrinterProfile("profile-a4", "A4", "A4", "BW", true),
                9
        );

        String selectedPrinter = strategy.select(List.of(wildcardPrinter, exactMatchPrinter), urgentJob)
                .orElseThrow()
                .getId();

        assertEquals("printer-exact", selectedPrinter);
    }

    @Test
    void reRegisterPrinterCanSetOnlineBackToTrue() {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());
        dispatcher.registerPrinter("printer-1", "Printer 1", false);
        assertFalse(dispatcher.getRegisteredPrinters().getFirst().isOnline());

        dispatcher.registerPrinter("printer-1", "Printer 1", true);

        assertTrue(dispatcher.getRegisteredPrinters().getFirst().isOnline());
    }
}
