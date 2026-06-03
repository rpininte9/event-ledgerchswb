package com.eventledger.gateway.config;

import com.eventledger.gateway.exception.AccountServiceClientException;
import com.eventledger.gateway.model.SubmitEventRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    private static final String CB_NAME = "accountService";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AccountServiceClient(RestTemplate restTemplate,
                                 @Value("${account-service.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "applyTransactionFallback")
    @Retry(name = CB_NAME)
    public Map<String, Object> applyTransaction(SubmitEventRequest event, String traceId) {
        String url = baseUrl + "/accounts/" + event.getAccountId() + "/transactions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", traceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("type", event.getType());
        body.put("amount", event.getAmount());
        body.put("currency", event.getCurrency());
        body.put("eventTimestamp", event.getEventTimestamp());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            log.info("Account service responded: status={} eventId={}", response.getStatusCode(), event.getEventId());
            return response.getBody();
        } catch (HttpClientErrorException e) {
            // 4xx — do not retry, propagate
            throw new AccountServiceClientException(e.getMessage(), e.getStatusCode().value());
        }
    }

    public Map<String, Object> applyTransactionFallback(SubmitEventRequest event, String traceId, Throwable t) {
        log.warn("Circuit breaker fallback triggered for eventId={}: {}", event.getEventId(), t.getMessage());
        throw new AccountServiceUnavailableException("Account service is currently unavailable");
    }

    public static class AccountServiceUnavailableException extends RuntimeException {
        public AccountServiceUnavailableException(String message) { super(message); }
    }
}
