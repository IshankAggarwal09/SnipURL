package com.ishank.urlshortener.services;

import com.ishank.urlshortener.config.KafkaConfig;
import com.ishank.urlshortener.dto.ClickEventMessage;
import com.ishank.urlshortener.models.ClickEvent;
import com.ishank.urlshortener.repository.ClickEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer that persists click events to the database.
 * Decoupled from the redirect hot path — events arrive asynchronously
 * via the "url-analytics-events" topic, preserving redirect latency.
 *
 * Uses String deserialization + Jackson 3 ObjectMapper to avoid
 * Jackson 2 vs 3 classpath conflicts in Spring Boot 4.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final ClickEventRepository clickEventRepository;
    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 1000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(
            topics = KafkaConfig.CLICK_EVENTS_TOPIC,
            groupId = "analytics-consumer-group"
    )
    @Transactional
    public void consume(String messageJson) {
        try {
            ClickEventMessage message = objectMapper.readValue(messageJson, ClickEventMessage.class);
            log.debug("Consuming click event for shortUrl={}", message.getShortUrl());

            ClickEvent event = ClickEvent.builder()
                    .shortUrl(message.getShortUrl())
                    .ipAddress(message.getIpAddress())
                    .userAgent(message.getUserAgent())
                    .country(message.getCountry() != null ? message.getCountry() : "IN")
                    .clickedAt(message.getClickedAt())
                    .build();

            clickEventRepository.save(event);
            log.info("Persisted click event for shortUrl={}", message.getShortUrl());
        } catch (Exception e) {
            log.error("Failed to process click event: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing click event, triggering retry/DLT", e);
        }
    }

    /**
     * Listener for Dead Letter Topic (DLT) when message processing fails 3 times.
     */
    @KafkaListener(
            topics = KafkaConfig.CLICK_EVENTS_DLT_TOPIC,
            groupId = "analytics-dlt-consumer-group"
    )
    public void consumeDlt(String failedMessageJson,
                           @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                           @Header(name = KafkaHeaders.OFFSET, required = false) Long offset) {
        log.error("Received failed click event in Dead Letter Topic [{}] at offset [{}]: {}",
                topic, offset, failedMessageJson);
    }
}
