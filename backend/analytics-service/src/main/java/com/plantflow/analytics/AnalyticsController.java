package com.plantflow.analytics;

import com.plantflow.shared.security.Actor;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service=service; }
    @GetMapping("/analytics") public AnalyticsService.Report report(@RequestParam(defaultValue="7") int days,Authentication auth) { return service.report(days,Actor.from(auth)); }
    @GetMapping("/analytics/export") public ResponseEntity<String> export(@RequestParam(defaultValue="7") int days,Authentication auth) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=plantflow-trips-"+days+"-days.csv").body(service.csv(days,Actor.from(auth)));
    }
    @GetMapping("/events") public List<AnalyticsService.Activity> events(Authentication auth) { return service.events(Actor.from(auth)); }
}
