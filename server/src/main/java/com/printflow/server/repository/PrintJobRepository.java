package com.printflow.server.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.printflow.sharedmodel.model.PrintJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PrintJobRepository {

    private static final TypeReference<Map<String, PrintJob>> JOB_MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final Map<String, PrintJob> jobs = new ConcurrentHashMap<>();

    public PrintJobRepository() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "printflow", "jobs.json"));
    }

    public PrintJobRepository(Path storagePath) {
        this(createDefaultObjectMapper(), storagePath);
    }

    public PrintJobRepository(ObjectMapper objectMapper, Path storagePath) {
        this.objectMapper = configureObjectMapper(objectMapper);
        this.storagePath = storagePath;
        load();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PrintJobRepository(@Value("${printflow.storage.jobs.file:data/printflow-jobs.json}") String storagePath) {
        this(createDefaultObjectMapper(), Path.of(storagePath));
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }

    public void save(PrintJob job) {
        if (job == null || job.getId() == null || job.getId().isBlank()) {
            throw new IllegalArgumentException("Job id must not be null or blank");
        }
        jobs.put(job.getId(), job);
        persist();
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

    private void load() {
        if (Files.notExists(storagePath)) {
            return;
        }

        try {
            if (Files.size(storagePath) == 0) {
                return;
            }

            Map<String, PrintJob> persisted = objectMapper.readValue(storagePath.toFile(), JOB_MAP_TYPE);
            if (persisted == null) {
                jobs.clear();
                return;
            }
            jobs.clear();
            jobs.putAll(persisted);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load jobs from storage: " + storagePath, e);
        }
    }

    private void persist() {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path tempFile = Files.createTempFile(parent == null ? Path.of(".") : parent, "printflow-jobs", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), jobs);
            try {
                Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist jobs to storage: " + storagePath, e);
        }
    }
}
