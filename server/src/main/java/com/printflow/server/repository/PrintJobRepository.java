package com.printflow.server.repository;

import com.printflow.sharedmodel.model.PrintJob;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PrintJobRepository {

    private final Map<String, PrintJob> jobs = new ConcurrentHashMap<>();

    public void save(PrintJob job) {
        jobs.put(job.getId(), job);
    }

    public Optional<PrintJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<PrintJob> findAll() {
        return jobs.values()
                .stream()
                .sorted(Comparator.comparing(PrintJob::getCreatedAt))
                .toList();
    }

    public List<PrintJob> findByUserId(String userId) {
        return jobs.values()
                .stream()
                .filter(job -> userId.equals(job.getUserId()))
                .sorted(Comparator.comparing(PrintJob::getCreatedAt))
                .toList();
    }
}
