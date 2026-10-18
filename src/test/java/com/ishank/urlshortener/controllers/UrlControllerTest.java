package com.ishank.urlshortener.controllers;

import com.ishank.urlshortener.dto.URLRequest;
import com.ishank.urlshortener.dto.URLResponse;
import com.ishank.urlshortener.services.AnalyticsService;
import com.ishank.urlshortener.services.UrlService;
import com.ishank.urlshortener.services.UrlService.AliasAlreadyExistsException;
import com.ishank.urlshortener.services.UrlService.UrlExpiredException;
import com.ishank.urlshortener.services.UrlService.UrlNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UrlControllerTest {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private UrlService urlService;
        private AnalyticsService analyticsService;
        private KafkaTemplate<String, String> kafkaTemplate;
        private HttpServletRequest request;
        private UrlController controller;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUp() {
                urlService = Mockito.mock(UrlService.class);
                analyticsService = Mockito.mock(AnalyticsService.class);
                kafkaTemplate = (KafkaTemplate<String, String>) Mockito.mock(KafkaTemplate.class);
                request = Mockito.mock(HttpServletRequest.class);
                controller = new UrlController(urlService, analyticsService, kafkaTemplate, objectMapper);
        }

        @Test
        void getLongURLByShortURL_redirectsWhenFound() {
                when(urlService.resolveLongUrl("code")).thenReturn("https://example.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn(null);
                when(request.getRemoteAddr()).thenReturn("127.0.0.1");
                when(request.getHeader("User-Agent")).thenReturn("TestAgent");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("code", request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://example.com");

                // Verify Kafka publish was called with correct topic and key
                verify(kafkaTemplate).send(eq("url-analytics-events"), eq("code"), anyString());
        }

        @Test
        void getLongURLByShortURL_publishesCorrectClickEventToKafka() {
                when(urlService.resolveLongUrl("abc")).thenReturn("https://google.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");
                when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

                controller.getLongURLByShortURL("abc", request);

                // Verify a JSON string containing the short URL was published
                verify(kafkaTemplate).send(eq("url-analytics-events"), eq("abc"), contains("\"shortUrl\":\"abc\""));
        }

        @Test
        void getLongURLByShortURL_returnsGoneWhenExpired() {
                Mockito.doThrow(new UrlExpiredException("expired")).when(urlService).resolveLongUrl("expired");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("expired", request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);

                // Verify Kafka was NOT called when URL is expired
                verifyNoInteractions(kafkaTemplate);
        }

        @Test
        void getLongURLByShortURL_returnsNotFoundWhenMissing() {
                Mockito.doThrow(new UrlNotFoundException("missing")).when(urlService).resolveLongUrl("missing");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("missing", request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

                // Verify Kafka was NOT called when URL is not found
                verifyNoInteractions(kafkaTemplate);
        }

        @Test
        void getLongURLByShortURL_stillRedirectsWhenKafkaFails() {
                when(urlService.resolveLongUrl("abc")).thenReturn("https://google.com");
                when(request.getHeader("X-Forwarded-For")).thenReturn(null);
                when(request.getRemoteAddr()).thenReturn("10.0.0.1");
                when(request.getHeader("User-Agent")).thenReturn("Chrome");

                // Simulate Kafka failure
                when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                        .thenThrow(new RuntimeException("Kafka broker unavailable"));

                // Redirect should still succeed despite Kafka failure
                ResponseEntity<Void> response = controller.getLongURLByShortURL("abc", request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://google.com");
        }

        @Test
        void createShortURL_returnsCreatedOnSuccess() {
                URLResponse response = URLResponse.builder()
                                .shortUrl("http://localhost:8080/api/urls/code")
                                .shortCode("code")
                                .longUrl("https://example.com")
                                .build();

                when(urlService.createShortUrl(any(URLRequest.class))).thenReturn(response);

                URLRequest req = new URLRequest();
                req.setLongUrl("https://example.com");

                ResponseEntity<URLResponse> resp = controller.createShortURL(req);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody().getShortCode()).isEqualTo("code");
        }

        @Test
        void createShortURL_returnsConflictOnAliasExists() {
                Mockito.doThrow(new AliasAlreadyExistsException("alias exists"))
                                .when(urlService).createShortUrl(any(URLRequest.class));

                URLRequest req = new URLRequest();
                req.setLongUrl("https://example.com");
                req.setCustomAlias("alias");

                ResponseEntity<URLResponse> resp = controller.createShortURL(req);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void health_returnsUpStatus() {
                ResponseEntity<Map<String, String>> resp = controller.health();

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody().get("status")).isEqualTo("UP");
                assertThat(resp.getBody().get("service")).isEqualTo("URL Shortener by Ishank Aggarwal");
        }

        @Test
        void getTopUrls_returnsTopUrlsFromAnalyticsService() {
                Map<String, Object> urlData = Map.of(
                        "shortCode", "xyz",
                        "longUrl", "https://example.com",
                        "clickCount", 42L
                );
                when(analyticsService.getTopUrls(5)).thenReturn(List.of(urlData));

                ResponseEntity<List<Map<String, Object>>> resp = controller.getTopUrls(5);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody()).hasSize(1);
                assertThat(resp.getBody().get(0)).containsEntry("shortCode", "xyz");
                assertThat(resp.getBody().get(0)).containsEntry("clickCount", 42L);
        }
}

