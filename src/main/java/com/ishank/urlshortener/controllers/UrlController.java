package com.ishank.urlshortener.controllers;

import com.ishank.urlshortener.config.KafkaConfig;
import com.ishank.urlshortener.dto.ClickEventMessage;
import com.ishank.urlshortener.dto.URLRequest;
import com.ishank.urlshortener.dto.URLResponse;
import com.ishank.urlshortener.services.AnalyticsService;
import com.ishank.urlshortener.services.UrlService;
import com.ishank.urlshortener.services.UrlService.AliasAlreadyExistsException;
import com.ishank.urlshortener.services.UrlService.UrlExpiredException;
import com.ishank.urlshortener.services.UrlService.UrlNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the URL Shortener API.
 *
 * <p>Exposes endpoints under {@code /api/urls} for creating short URLs,
 * resolving them to their original destinations, and checking service health.
 * Click events are published asynchronously to Kafka so that redirect latency
 * is never blocked by analytics processing.</p>
 *
 * @author Ishank Aggarwal
 */
@Slf4j
@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Resolves a short URL code and redirects the client to the original long URL.
     *
     * <p>On a successful lookup the response is HTTP {@code 302 Found} with the
     * {@code Location} header set to the original URL. A click-event is published
     * to Kafka in a fire-and-forget manner — Kafka failures do NOT affect the
     * redirect.</p>
     *
     * @param shortUrl the Base62-encoded short code (e.g. {@code "aB3xZ"})
     * @param request  the incoming HTTP request, used to extract client IP and
     *                 {@code User-Agent} for analytics
     * @return {@code 302 Found}      — redirect to the long URL<br>
     *         {@code 410 Gone}       — the short URL existed but has expired<br>
     *         {@code 404 Not Found}  — no such short URL exists
     */
    @GetMapping("/{shortUrl}")
    public ResponseEntity<Void> getLongURLByShortURL(@NotNull @PathVariable("shortUrl") String shortUrl,
            HttpServletRequest request) {
        try {
            String longUrl = urlService.resolveLongUrl(shortUrl);

            // Fire-and-forget async publish to Kafka — non-blocking (~1ms)
            // Analytics are persisted by ClickEventConsumer in the background
            try {
                ClickEventMessage clickEvent = ClickEventMessage.builder()
                        .shortUrl(shortUrl)
                        .ipAddress(resolveClientIp(request))
                        .userAgent(request.getHeader("User-Agent"))
                        .clickedAt(LocalDateTime.now())
                        .build();
                String json = objectMapper.writeValueAsString(clickEvent);
                kafkaTemplate.send(KafkaConfig.CLICK_EVENTS_TOPIC, shortUrl, json);
            } catch (Exception e) {
                // Kafka publish failure should NOT block the redirect
                log.warn("Failed to publish click event to Kafka for shortUrl={}: {}", shortUrl, e.getMessage());
            }

            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(longUrl)).build();
        } catch (UrlExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        } catch (UrlNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Creates a new short URL from the provided long URL.
     *
     * <p>Optionally accepts a custom alias. If the alias is already in use a
     * {@code 409 Conflict} is returned. On success the response body contains
     * the generated short code and full short URL.</p>
     *
     * @param urlRequest the request body containing the long URL, optional custom
     *                   alias, and optional expiration time; must be valid per
     *                   bean-validation constraints on {@link URLRequest}
     * @return {@code 201 Created}   — body is the {@link URLResponse} with the short URL<br>
     *         {@code 409 Conflict}  — the requested custom alias is already taken
     */
    @PostMapping
    public ResponseEntity<URLResponse> createShortURL(@Valid @RequestBody URLRequest urlRequest) {
        try {
            URLResponse response = urlService.createShortUrl(urlRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AliasAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(URLResponse.builder().shortUrl("error").build());
        }
    }

    /**
     * Health-check endpoint for the URL Shortener service.
     *
     * <p>Returns a lightweight JSON payload confirming the service is reachable.
     * Suitable for use by load balancers, uptime monitors, and CI pipelines.</p>
     *
     * @return {@code 200 OK} with body
     *         {@code {"status": "UP", "service": "URL Shortener by Ishank Aggarwal"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "URL Shortener by Ishank Aggarwal"
        ));
    }

    /**
     * Retrieves the top most clicked short URLs ordered by total clicks descending.
     *
     * @param limit maximum number of top URLs to return (default 10)
     * @return {@code 200 OK} with list of top URLs and their click counts
     */
    @GetMapping("/top")
    public ResponseEntity<List<Map<String, Object>>> getTopUrls(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        List<Map<String, Object>> topUrls = analyticsService.getTopUrls(limit);
        return ResponseEntity.ok(topUrls);
    }

    /**
     * Resolves the real client IP address, honouring the {@code X-Forwarded-For}
     * header that is set by reverse proxies and load balancers.
     *
     * @param request the incoming HTTP request
     * @return the client's IP address string
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
