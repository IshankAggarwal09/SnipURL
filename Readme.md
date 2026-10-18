# SnipURL

A high-performance URL shortening and analytics service built with **Spring Boot**, **Apache Kafka** (KRaft mode), **PostgreSQL**, and **Redis**. Supports custom aliases, vanity URLs, link expiration, click analytics, and asynchronous event streaming with Dead Letter Topic retries.

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen?logo=spring)
![Kafka](https://img.shields.io/badge/Kafka-KRaft-black?logo=apachekafka)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?logo=postgresql)
![Redis](https://img.shields.io/badge/Redis-latest-red?logo=redis)
![Coverage](https://img.shields.io/badge/Coverage-100%25-brightgreen.svg)
![Tests](https://img.shields.io/badge/Tests-48%20passed-brightgreen.svg)

---

## Author

**Built by Ishank Aggarwal | GitHub: [IshankAggarwal09](https://github.com/IshankAggarwal09) | LinkedIn: [ishank-aggarwal](https://linkedin.com/in/ishank-aggarwal)**

---

## Core System Architecture & Highlights

Designed and developed from the ground up as a production-grade distributed URL shortening and real-time analytics platform:

- **Domain-Based Vanity Code Generation**: Generates deterministic, human-readable short links derived from the domain label and an alphanumeric random suffix (e.g. `https://github.com/ishank` → `github-a3f`).
- **Phishing & Nested Redirect Defense**: In-flight URL blacklist engine that intercepts and rejects circular redirects or nested shortener domains (`bit.ly`, `tinyurl.com`, `t.co`) with custom domain exceptions.
- **Real-Time Click Analytics & Top URLs Leaderboard**: Dedicated aggregated query layer exposed via `GET /api/urls/top?limit=10` supporting real-time top-performing links and geographic visitor tracking.
- **Production-Grade Kafka Event Pipeline**: Decoupled async click ingestion using Apache Kafka (KRaft mode). Keeps redirect latency under ~4ms, backed by `@RetryableTopic` (3 attempts, 1s backoff) and automated Dead Letter Topic (`url-analytics-events-dlt`) error handling.
- **Configurable Token-Bucket Rate Limiter**: IP-based rate limiting powered by Bucket4j, fully configurable via `application.yaml`, offering instant abuse mitigation with health probe bypasses.
- **Multi-Layer Caching (Redis)**: Bidirectional caching (`alias → longUrl` and `longUrl → alias`) minimizing PostgreSQL reads for high-traffic redirects.

---

## Features

- **Shorten URLs** — paste a long URL, get a short link
- **Custom Aliases & Vanity Codes** — choose your own alias or use automated domain-based vanity codes
- **Blacklist Protection** — prevents recursive short links from known shortener domains
- **Expiration** — optional expiry date for time-sensitive links
- **Click Analytics & Country Tracking** — tracks clicks, IPs, user agents, and geographic country codes via `/stats/{alias}`
- **Top URLs Leaderboard** — real-time view of top performing short URLs
- **Async Analytics (Kafka)** — click events published to Kafka, consumed and persisted in the background. Keeps redirect latency low (~4ms vs ~18ms with sync writes)
- **DLT & Retry Queue** — 3 retry attempts with backoff and Dead Letter Topic routing for resilient Kafka processing
- **Redis Caching** — bidirectional cache (`short→long`, `long→short`) to reduce DB hits
- **Configurable Rate Limiting** — Bucket4j-based IP throttling with health-check endpoint bypass
- **Scheduled Cleanup** — cron job removes expired URLs from DB and cache
- **Flyway Migrations** — versioned database schema changes
- **Thymeleaf UI** — dark-themed web interface with interactive character counter and one-click clipboard copy
- **Input Validation** — regex validation to prevent XSS and open redirects
- **Graceful Degradation** — redirects continue functioning seamlessly if Kafka is temporarily offline
- **Test Coverage** — 48 JUnit 5 + Mockito unit and integration tests with JaCoCo enforcement

---

## Architecture

```
┌──────────────┐       ┌──────────────────────────────────────────────┐
│   Browser    │       │             Spring Boot App                  │
│              │──────▶│                                              │
│  POST /api   │       │  urlController ──▶ UrlService ──▶ PostgreSQL │
│              │       │       │                  │                   │
│  GET /{code} │       │       │ resolve()        │ Redis Cache       │
│              │◀──302─│       ▼                  ▼                   │
│              │       │  KafkaTemplate.send()  (fire-and-forget)     │
└──────────────┘       └──────────┬───────────────────────────────────┘
                                  │ ~1ms async
                                  ▼
                          ┌──────────────┐
                          │    Kafka     │
                          │  (KRaft)    │
                          │             │
                          │ Topic:      │
                          │ url-click-  │
                          │ events      │
                          └──────┬───────┘
                                 │
                                 ▼
                       ┌──────────────────┐
                       │ ClickEvent       │
                       │ Consumer         │
                       │                  │
                       │ @KafkaListener   │
                       │ ──▶ DB INSERT    │
                       └──────────────────┘
```

**Before Kafka**: `GET /{code}` → Redis lookup → **DB INSERT (sync, ~15ms)** → 302 redirect

**After Kafka**: `GET /{code}` → Redis lookup → **Kafka publish (~1ms)** → 302 redirect → Consumer persists in background

---

## Tech Stack

| Layer                  | Technology                                       |
| ---------------------- | ------------------------------------------------ |
| Backend                | Java 21, Spring Boot 4                           |
| Event Streaming        | Apache Kafka (KRaft mode — no ZooKeeper)         |
| Web UI                 | Thymeleaf, Vanilla HTML/CSS/JS                   |
| Database               | PostgreSQL 15                                    |
| Cache & Rate Limiting  | Redis, Bucket4j                                  |
| ORM                    | Spring Data JPA / Hibernate                      |
| Schema Management      | Flyway                                           |
| Build & Testing        | Maven, JUnit 5, Mockito, JaCoCo                 |
| Containers             | Docker Compose                                   |

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3+
- Docker & Docker Compose

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL** on `localhost:5431`
- **Redis** on `localhost:6379`
- **Kafka (KRaft)** on `localhost:9092`

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

The app starts at **http://localhost:8080** and Flyway will automatically run the database migrations.

### 3. Open the UI

Navigate to **http://localhost:8080** in your browser to use the web interface.

### 4. Stop Everything

```bash
docker-compose down
```

---

## Web UI

The app provides a modern, dark-themed interface:

- **Shorten Form** — destination URL, optional custom alias with an interactive 8-char live counter, and expiry date picker.
- **One-Click Copy** — Clipboard API copy button next to generated short URLs with immediate feedback.
- **Analytics Dashboard** — view total clicks, recent click details, and top 5 most clicked links fetched dynamically.
- **Error Handling** — clear alerts for blacklist violations, conflicts, and validation errors.

---

## REST API

Base URL: `http://localhost:8080`

### 1. Create Short URL

```http
POST /api/urls
Content-Type: application/json

{
  "longUrl": "https://example.com/very/long/path",
  "customAlias": "my-link",
  "expirationTime": "2026-12-31T23:59:59"
}
```

| Field            | Required | Description                                       |
| ---------------- | -------- | ------------------------------------------------- |
| `longUrl`        | ✅        | Target URL to shorten                             |
| `customAlias`    | ❌        | Desired short code (1–8 chars, Alphanumeric only) |
| `expirationTime` | ❌        | ISO-8601 datetime for expiry                      |

**Response** (`201 Created`):

```json
{
  "shortUrl": "http://localhost:8080/api/urls/my-link",
  "shortCode": "my-link",
  "longUrl": "https://example.com/very/long/path",
  "expirationTime": "2026-12-31T23:59:59"
}
```

**Error Responses**:
- `409 Conflict` — custom alias already taken.
- `400 Bad Request` — validation failure or blacklisted domain.
- `429 Too Many Requests` — rate limit exceeded:
  ```json
  {
    "error": "Rate limit exceeded",
    "message": "You have exceeded 20 requests per minute. Please slow down.",
    "retryAfter": 60
  }
  ```

### 2. Redirect Short URL

```http
GET /api/urls/{shortCode}
```

| Status          | Condition                                   |
| --------------- | ------------------------------------------- |
| `302 Found`     | Active, not expired → redirects to long URL |
| `410 Gone`      | Expired → marks URL inactive                |
| `404 Not Found` | Short code doesn't exist                    |

### 3. Top Clicked URLs

```http
GET /api/urls/top?limit=10
```

**Response** (`200 OK`):

```json
[
  {
    "shortCode": "gh-repo",
    "longUrl": "https://github.com/IshankAggarwal09",
    "clickCount": 1420
  }
]
```

### 4. Health Check

```http
GET /api/urls/health
```

**Response** (`200 OK`):

```json
{
  "status": "UP",
  "service": "URL Shortener by Ishank Aggarwal"
}
```

---

## Kafka Analytics

### How It Works

1. **Producer** (`UrlController`): On each redirect, a `ClickEventMessage` (shortUrl, IP, userAgent, country, timestamp) is serialized to JSON and published to the `url-analytics-events` Kafka topic using fire-and-forget.

2. **Kafka Broker** (KRaft mode): Stores click events in a 3-partition topic. Messages are keyed by `shortCode` for partition ordering.

3. **Consumer & Retry Logic** (`ClickEventConsumer`):
   - A `@KafkaListener` backed by `@RetryableTopic` consumes events and persists them to the PostgreSQL `click_events` table.
   - Retries up to 3 times with a 1-second backoff on transient errors.
   - Unrecoverable messages route to the Dead Letter Topic (`url-analytics-events-dlt`), logged by dedicated error consumer handlers.

### Why Kafka over direct DB insert?

| Concern | Before (sync) | After (Kafka) |
|---|---|---|
| Redirect latency | ~18ms | ~4ms |
| DB under load | Redirects slow down | Redirects unaffected |
| DB goes down | Redirects fail (500) | Redirects work, events queue in Kafka |
| Viral traffic spike | DB overwhelmed | Consumer processes at its own pace |

### Why KRaft instead of ZooKeeper?

ZooKeeper was **deprecated in Kafka 3.3** and **removed in Kafka 4.0**. KRaft replaces ZooKeeper's external consensus with an internal Raft-based metadata quorum — eliminating the need for a separate ZooKeeper cluster, slashing controller failover from minutes to milliseconds, and supporting millions of partitions.

---

## Configuration

All config lives in `src/main/resources/application.yaml`:

| Property                 | Default                                          | Description |
| ------------------------ | ------------------------------------------------ | ----------- |
| Server port              | `8080`                                           | HTTP server port |
| PostgreSQL URL           | `jdbc:postgresql://localhost:5431/url_shortener` | Database connection URL |
| PostgreSQL user          | `url_shortener_user`                             | Database username |
| Redis host/port          | `localhost:6379`                                 | Redis cache & rate limiter |
| Kafka bootstrap servers  | `localhost:9092`                                 | Kafka broker address |
| Kafka consumer group     | `analytics-consumer-group`                       | Consumer group identifier |
| Base URL for short links | `http://localhost:8080/api/urls`                 | Public URL prefix |
| Rate limit               | `20 requests per minute`                         | `app.rate-limit.requests-per-minute` |
| Cleanup Cron             | `0 0 * * * *`                                    | Runs hourly background cleanup |

---

## Testing

```bash
mvn clean test
```

**48 tests** with comprehensive test coverage enforced by JaCoCo:

| Test Class                | Tests | What it covers |
|---------------------------|:-----:|----------------|
| `UrlControllerTest`       | 8     | REST endpoints, `/top` ranking, Kafka publish verification, health check |
| `WebControllerTest`       | 5     | Thymeleaf template rendering and form validation |
| `UrlServiceTest`          | 12    | Base62 encoding, vanity codes, blacklist checks, caching, expiration |
| `AnalyticsServiceTest`    | 6     | Click tracking, country attribution, top URLs aggregations |
| `ClickEventConsumerTest`  | 4     | Consumer persistence, retry triggers, Dead Letter Topic processing |
| `CleanupServiceTest`      | 2     | Scheduled cleanup of expired URLs |
| `RateLimitFilterTest`     | 4     | Bucket4j rate limits, custom 429 response body, health bypass (100+ reqs) |
| `UrlShortenerApplicationTests` | 1 | Spring application context startup |
| `Base62EncoderTest`       | 6     | Bijective Base62 encoding and decoding |

---

## Project Structure

```
src/main/java/com/ishank/urlshortener/
├── UrlShortenerApplication.java        # Entry point (@EnableKafka)
├── config/
│   ├── KafkaConfig.java               # Kafka producer/consumer/topic & DLT config
│   ├── RateLimitFilter.java           # Bucket4j configurable rate limiter
│   └── FilterConfig.java             # Web filter registration
├── controllers/
│   ├── UrlController.java             # REST API, health endpoint, top URLs & Kafka producer
│   └── WebController.java            # Thymeleaf UI controller
├── dto/
│   ├── URLRequest.java               # Request DTO
│   ├── URLResponse.java              # Response DTO
│   ├── AnalyticsResponse.java        # Analytics DTO
│   └── ClickEventMessage.java        # Kafka message DTO
├── exceptions/
│   └── BlacklistedUrlException.java   # Custom domain validation exception
├── models/
│   ├── URL.java                      # JPA entity
│   └── ClickEvent.java              # Analytics data model with country tracking
├── repository/
│   ├── UrlRepository.java           # URL Spring Data repository
│   └── ClickEventRepository.java    # Analytics & aggregated queries repository
└── services/
    ├── UrlService.java               # Core logic, Base62, vanity generator & blacklist validator
    ├── AnalyticsService.java         # Click recording, country derivation & top URLs query
    ├── ClickEventConsumer.java       # Kafka consumer with @RetryableTopic & DLT listener
    ├── CleanupService.java           # Scheduled cron cleanup
    └── Base62Encoder.java            # Short code generator

src/main/resources/
├── application.yaml                   # App configuration
├── db/migration/                      # Flyway SQL schema migrations
└── templates/
    ├── index.html                     # Thymeleaf web UI with live counter & copy button
    └── analytics.html                 # Analytics dashboard with top 5 URLs table
```

---

## License

This project is open source and available under the [MIT License](LICENSE).