package com.eventledger.gateway.service;

import com.eventledger.gateway.config.AccountServiceClient;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        MeterRegistry meterRegistry,
                        ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> submitEvent(SubmitEventRequest request, String traceId) {
        meterRegistry.counter("events_received_total").increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Idempotency check
            Optional<EventRecord> existing = eventRepository.findById(request.getEventId());
            if (existing.isPresent()) {
                meterRegistry.counter("events_duplicate_total").increment();
                log.info("Duplicate event detected: eventId={}", request.getEventId());
                Map<String, Object> response = toMap(existing.get());
                response.put("duplicate", true);
                return response;
            }

            // Persist event in Gateway DB
            EventRecord record = new EventRecord();
            record.setEventId(request.getEventId());
            record.setAccountId(request.getAccountId());
            record.setType(request.getType());
            record.setAmount(request.getAmount());
            record.setCurrency(request.getCurrency());
            record.setEventTimestamp(request.getEventTimestamp());
            record.setReceivedAt(Instant.now());
            if (request.getMetadata() != null) {
                try {
                    record.setMetadata(objectMapper.writeValueAsString(request.getMetadata()));
                } catch (Exception e) {
                    log.warn("Could not serialize metadata for eventId={}", request.getEventId());
                }
            }
            eventRepository.save(record);

            // Forward to Account Service
            accountServiceClient.applyTransaction(request, traceId);

            log.info("Event successfully processed: eventId={}", request.getEventId());
            Map<String, Object> response = toMap(record);
            response.put("duplicate", false);
            return response;

        } catch (Exception e) {
            meterRegistry.counter("events_errors_total").increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("events_processing_duration_seconds"));
        }
    }

    public Optional<EventRecord> getEvent(String eventId) {
        return eventRepository.findById(eventId);
    }

    public List<EventRecord> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
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
