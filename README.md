# Event Ledger

A distributed financial transaction processing system composed of two independently runnable Spring Boot microservices.

<img width="704" height="384" alt="Application Overview" src="https://github.com/user-attachments/assets/67277215-ea45-493c-8f0e-0bcffb267c11" />


---

## Architecture Overview

```
Browser / Client
      │
      │  REST (HTTP)
      ▼
┌─────────────────────────────────────────┐
│           Event Gateway  (:8080)        │
│                                         │
│  • Receives & validates events          │
│  • Enforces idempotency (H2 DB)         │
│  • Assigns / propagates Trace IDs       │
│  • Circuit Breaker → Account Service    │
│  • Exposes: /events, /health, /metrics  │
└─────────────────┬───────────────────────┘
                  │  REST (HTTP) + X-Trace-Id header
                  ▼
┌─────────────────────────────────────────┐
│         Account Service  (:8081)        │
│                                         │
│  • Manages account state (H2 DB)        │
│  • Applies transactions (idempotent)    │
│  • Recomputes balance from all TXs      │
│  • Exposes: /accounts, /health          │
└─────────────────────────────────────────┘
```

### Key Design Decisions

| Concern | Decision |
|---|---|
| **Idempotency** | Gateway checks `eventId` before persisting. Account Service checks `eventId` before applying. Both layers protect independently. |
| **Out-of-order** | Account Service always recomputes balance with `SUM(CREDIT) - SUM(DEBIT)` across all stored transactions. Arrival order is irrelevant. |
| **Service separation** | Each service owns its own H2 in-memory database. No shared state whatsoever. |
| **Tracing** | Gateway generates a UUID trace ID per request (or accepts one via `X-Trace-Id`). Propagated to Account Service via the same header. Both services log it in structured JSON via MDC. |
| **Resiliency** | Resilience4j **Circuit Breaker + Retry with exponential backoff** on the Gateway → Account Service call (see below). |
| **Metrics** | Micrometer counters and timers exposed via `/actuator/prometheus`. |

---

## Resiliency Pattern: Circuit Breaker + Retry

The Event Gateway wraps every call to the Account Service with **Resilience4j Circuit Breaker** and **Retry with exponential backoff**.

### Why Circuit Breaker?

Without a circuit breaker, a failing Account Service causes every `POST /events` call to hang until timeout, exhausting the Gateway's thread pool and cascading failures to all clients. With a circuit breaker:

1. After 50% of the last 10 calls fail, the circuit **opens** — subsequent calls immediately return `503 Service Unavailable` without blocking.
2. After 10 seconds, the circuit transitions to **half-open**, allowing 3 probe requests.
3. If the probes succeed, the circuit **closes** and normal operation resumes.

### Why Retry with Exponential Backoff?

Transient failures (brief network hiccups, GC pauses) can be safely retried. The Gateway retries up to **3 times** with **500ms → 1s → 2s** delays before giving up. Only retriable exceptions (connection errors, timeouts) trigger retries; HTTP 4xx client errors are **not** retried.

### Graceful Degradation

| Endpoint | Account Service Down |
|---|---|
| `POST /events` | Returns `503 Service Unavailable` |
| `GET /events/{id}` | ✅ Works — reads Gateway's local DB only |
| `GET /events?account=...` | ✅ Works — reads Gateway's local DB only |
| `GET /accounts/{id}/balance` | Returns `503` via circuit breaker fallback |

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (optional, for containerized run)
- `curl` or Postman (for manual testing)

---

## Running the Services

### Option 1: Docker Compose (Recommended)

```bash
# From the project root
docker-compose up --build

# Verify both services are up
curl http://localhost:8080/health
curl http://localhost:8081/health
```

### Option 2: Run Locally (Two Terminals)

**Terminal 1 — Account Service:**
```bash
cd account-service
mvn spring-boot:run
# Starts on http://localhost:8081
```

**Terminal 2 — Event Gateway:**
```bash
cd event-gateway
mvn spring-boot:run
# Starts on http://localhost:8080
```

---

## Running the Tests

```bash
# From project root
cd event-gateway && mvn test
cd ../account-service && mvn test
```

### What the tests cover

| Test | Class | What it proves |
|---|---|---|
| Submit event (201) | `EventControllerIntegrationTest` | New event stored and forwarded |
| Duplicate idempotency (200 + flag) | `EventControllerIntegrationTest` | Same eventId not forwarded twice |
| Validation errors (400) | `EventControllerIntegrationTest` | Missing fields, bad type, negative amount |
| 503 when Account Service is down | `EventControllerIntegrationTest` | Graceful error, not 500 or hang |
| GET works when Account Service is down | `EventControllerIntegrationTest` | Gateway reads its own DB independently |
| **Circuit breaker opens after failures** | `EventControllerIntegrationTest` | CB state = OPEN; calls short-circuit |
| **No retry on 4xx** | `EventControllerIntegrationTest` | WireMock verify called exactly once |
| **Trace ID propagated to Account Service** | `EventControllerIntegrationTest` | WireMock verifies X-Trace-Id header |
| **End-to-end full flow** | `EventControllerIntegrationTest` | Submit → duplicate → out-of-order → list in order |
| Credit/debit balance | `AccountControllerIntegrationTest` | SUM(CREDIT) - SUM(DEBIT) |
| Idempotency (ALREADY_APPLIED) | `AccountControllerIntegrationTest` | Balance unchanged on duplicate |
| Out-of-order correct balance | `AccountControllerIntegrationTest` | Arrival order does not affect result |
| Chronological transaction list | `AccountControllerIntegrationTest` | Ordered by eventTimestamp, not insertion |
| Trace ID echo | `AccountControllerIntegrationTest` | X-Trace-Id echoed in response header |
| Validation | `AccountControllerIntegrationTest` | Missing fields, zero amount |

---

## API Reference

### Event Gateway (`localhost:8080`)

#### Submit Event
```
POST /events
Content-Type: application/json

{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```
- **201 Created** — new event stored and applied
- **200 OK** + `"duplicate": true` — same `eventId` already exists
- **400 Bad Request** — validation failure
- **503 Service Unavailable** — Account Service unreachable

#### Get Event
```
GET /events/{id}
```

#### List Events by Account (chronological)
```
GET /events?account={accountId}
```

#### Health
```
GET /health
```

---

### Account Service (`localhost:8081`)

#### Apply Transaction
```
POST /accounts/{accountId}/transactions
```

#### Get Balance
```
GET /accounts/{accountId}/balance
```

#### Get Account Details + Transactions
```
GET /accounts/{accountId}
```

#### Health
```
GET /health
```

---

## Observability

### Structured Logging (JSON)
```json
{"timestamp":"2026-05-15 14:02:11.123","level":"INFO","service":"event-gateway","traceId":"a1b2c3d4...","logger":"EventService","message":"Event successfully processed: eventId=evt-001"}
```

### Metrics (Prometheus)
```
GET http://localhost:8080/actuator/prometheus
GET http://localhost:8081/actuator/prometheus
```

Custom metrics:
- `events_received_total`
- `events_duplicate_total`
- `events_errors_total`
- `events_processing_duration_seconds`
- `account_transactions_applied_total`
- `account_transactions_credit_total` / `account_transactions_debit_total`

### Circuit Breaker State
```
GET http://localhost:8080/actuator/circuitbreakers
```

### H2 Console (dev only)
- Gateway: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:gatewaydb`)
- Account Service: `http://localhost:8081/h2-console` (JDBC URL: `jdbc:h2:mem:accountdb`)

---

## Example curl Flow

```bash
# 1. Submit a CREDIT
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":500.00,"currency":"USD","eventTimestamp":"2026-05-10T10:00:00Z"}'

# 2. Submit a DEBIT (out of order — earlier timestamp submitted second)
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-002","accountId":"acct-123","type":"DEBIT","amount":150.00,"currency":"USD","eventTimestamp":"2026-05-09T08:00:00Z"}'

# 3. List events (chronological: evt-002 then evt-001)
curl -s "http://localhost:8080/events?account=acct-123" | jq .

# 4. Check balance (should be 350.00)
curl -s http://localhost:8081/accounts/acct-123/balance | jq .

# 5. Re-submit evt-001 — returns 200 with duplicate:true
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":500.00,"currency":"USD","eventTimestamp":"2026-05-10T10:00:00Z"}' | jq .
```
## Results

### Account Service – Startup
<img width="952" height="110" alt="Account Service Front End" src="https://github.com/user-attachments/assets/cf0140d5-59df-4bc4-a3cf-963b20455130" />

### Event Gateway Service – Startup
<img width="954" height="100" alt="Event Gateway Service Front End" src="https://github.com/user-attachments/assets/da1de2a7-e5fd-4dbb-ab8a-5ea626d44bd6" />

### Account Service – Integration Test Results
<img width="924" height="152" alt="AccountControllerIntegrationTest Results" src="https://github.com/user-attachments/assets/6ca10640-6cca-4517-b47b-e963e256cb1d" />

### Event Gateway Service – Integration Test Results
<img width="938" height="153" alt="EventControllerIntegrationTest Results" src="https://github.com/user-attachments/assets/d24c9975-115e-47a3-8950-fa9afdc4cd87" />


