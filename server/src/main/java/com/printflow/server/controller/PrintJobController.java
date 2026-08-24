package com.printflow.server.controller;

import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.dto.PrintJobResponse;
import com.printflow.sharedmodel.dto.PrintResultResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class PrintJobController {

    private final PrintJobService printJobService;

    public PrintJobController(PrintJobService printJobService) {
        this.printJobService = printJobService;
    }

    @PostMapping
    public ResponseEntity<PrintJobResponse> createJob(@Valid @RequestBody CreatePrintJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(printJobService.createJob(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrintJobResponse> getJob(@PathVariable String id) {
        return ResponseEntity.ok(printJobService.getJob(id));
    }

    @GetMapping
    public ResponseEntity<List<PrintJobResponse>> listJobs(@RequestParam(required = false) String userId) {
        return ResponseEntity.ok(printJobService.listJobs(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<PrintJobResponse> cancelJob(@PathVariable String id) {
        return ResponseEntity.ok(printJobService.cancelJob(id));
    }

    @GetMapping("/{id}/result")
    public ResponseEntity<PrintResultResponse> getResult(@PathVariable String id) {
        return ResponseEntity.ok(printJobService.getResult(id));
    }
}
