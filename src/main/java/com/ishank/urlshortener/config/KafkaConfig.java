package com.ishank.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for async click event processing.
 *
 * <h3>Why KRaft Mode Instead of ZooKeeper?</h3>
 * <p>
 * Apache Kafka has fully deprecated and removed ZooKeeper support in Kafka 4.0.
 * KRaft (Kafka Raft Metadata mode) provides event-driven consensus directly
 * inside Kafka brokers, eliminating the external ZooKeeper cluster dependency.
 * Benefits include:
 * <ul>
 *   <li>Simpler operations, single system to monitor and secure.</li>
 *   <li>Sub-second controller failover times compared to minutes with ZooKeeper.</li>
 *   <li>Higher metadata scalability supporting millions of partitions per cluster.</li>
 * </ul>
 * </p>
 *
 * Uses String serialization/deserialization throughout to avoid
 * Jackson 2 vs 3 classpath conflicts in Spring Boot 4.
 * JSON conversion is handled in the controller/consumer via
 * Jackson 3's ObjectMapper.
 */
@Configuration
public class KafkaConfig {

    public static final String CLICK_EVENTS_TOPIC = "url-analytics-events";
    public static final String CLICK_EVENTS_DLT_TOPIC = "url-analytics-events-dlt";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ── Topics ─────────────────────────────────────────────────────────────

    @Bean
    public NewTopic clickEventsTopic() {
        return TopicBuilder.name(CLICK_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic clickEventsDltTopic() {
        return TopicBuilder.name(CLICK_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // ── Producer ───────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer ───────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "analytics-consumer-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        return factory;
    }
}
