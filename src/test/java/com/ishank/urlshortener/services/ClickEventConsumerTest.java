package com.ishank.urlshortener.services;

import com.ishank.urlshortener.models.ClickEvent;
import com.ishank.urlshortener.repository.ClickEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClickEventConsumerTest {

    private ClickEventRepository clickEventRepository;
    private ClickEventConsumer clickEventConsumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clickEventRepository = mock(ClickEventRepository.class);
        clickEventConsumer = new ClickEventConsumer(clickEventRepository, objectMapper);
    }

    @Test
    void consume_persistsClickEventCorrectly() {
        String json = """
                {"shortUrl":"abc123","ipAddress":"203.0.113.42","userAgent":"Mozilla/5.0","clickedAt":"2026-05-25T12:00:00"}
                """;

        clickEventConsumer.consume(json);

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(captor.capture());

        ClickEvent saved = captor.getValue();
        assertThat(saved.getShortUrl()).isEqualTo("abc123");
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.42");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getClickedAt()).isNotNull();
    }

    @Test
    void consume_handlesNullUserAgent() {
        String json = """
                {"shortUrl":"xyz789","ipAddress":"10.0.0.1","userAgent":null,"clickedAt":"2026-05-25T14:30:00"}
                """;

        clickEventConsumer.consume(json);

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(captor.capture());

        ClickEvent saved = captor.getValue();
        assertThat(saved.getShortUrl()).isEqualTo("xyz789");
        assertThat(saved.getUserAgent()).isNull();
    }

    @Test
    void consume_handlesInvalidJson_throwsExceptionForRetryAndDlt() {
        String invalidJson = "not-valid-json";

        // Must throw so Spring Kafka retry / DLT mechanism triggers
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> {
            clickEventConsumer.consume(invalidJson);
        });

        verifyNoInteractions(clickEventRepository);
    }

    @Test
    void consumeDlt_logsFailedMessageWithoutThrowing() {
        // Verifies the dead letter topic consumer consumes without throwing
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            clickEventConsumer.consumeDlt("{\"invalid\":\"payload\"}", "url-analytics-events-dlt", 10L);
        });
    }
}
