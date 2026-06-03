package com.eventledger.gateway.controller;

import com.eventledger.gateway.config.AccountServiceClient;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.eventledger.gateway.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> submitEvent(
            @Valid @RequestBody SubmitEventRequest request,
            HttpServletRequest httpRequest) {
        String traceId = (String) httpRequest.getAttribute("X-Trace-Id");
        try {
            Map<String, Object> result = eventService.submitEvent(request, traceId);
            boolean isDuplicate = Boolean.TRUE.equals(result.get("duplicate"));
            return isDuplicate
                    ? ResponseEntity.ok(result)
                    : ResponseEntity.status(201).body(result);
        } catch (AccountServiceClient.AccountServiceUnavailableException e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Account service unavailable", "message", e.getMessage()));
        }
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> getEvent(@PathVariable String eventId) {
        return eventService.getEvent(eventId)
                .map(e -> ResponseEntity.ok(toMap(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        List<Map<String, Object>> events = eventService.getEventsByAccount(accountId)
                .stream().map(this::toMap).toList();
        return ResponseEntity.ok(events);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "event-gateway");
        response.put("timestamp", java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).toString()); // Timestamp in local system timezone
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toMap(EventRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", r.getEventId());
        m.put("accountId", r.getAccountId());
        m.put("type", r.getType());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("eventTimestamp", r.getEventTimestamp());
        m.put("receivedAt", r.getReceivedAt());
        return m;
    }
}
