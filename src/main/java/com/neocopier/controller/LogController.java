package com.neocopier.controller;

import com.neocopier.service.LogService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping
    public Map<String, List<String>> getLogs(@RequestParam(value = "lines", defaultValue = "500") int lines) {
        return Map.of("logs", logService.readLastLogLines(lines));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadLogs() {
        File logFile = logService.getLogFile();
        if (!logFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(logFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"app.log\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/clear")
    public Map<String, Boolean> clearLogs() {
        logService.clearLogFile();
        return Map.of("success", true);
    }
}
