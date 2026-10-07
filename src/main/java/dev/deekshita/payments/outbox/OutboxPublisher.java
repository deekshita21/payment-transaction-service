package dev.deekshita.payments.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and publishes pending events to Kafka, keyed by aggregate id so events
 * for the same transfer stay ordered within a partition. Delivery is at-least-once:
 * consumers must de-duplicate on the eventId header.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           @Value("${app.outbox.topic}") String topic,
                           @Value("${app.outbox.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outbox.findUnpublished(PageRequest.of(0, batchSize));
        for (OutboxEvent event : pending) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.getAggregateId().toString(), event.getPayload());
            record.headers().add("eventId", event.getId().toString().getBytes());
            record.headers().add("eventType", event.getEventType().getBytes());
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS);
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailedAttempt();
                return;
            } catch (Exception e) {
                event.recordFailedAttempt();
                log.warn("Failed to publish outbox event id={} attempt={}; will retry",
                        event.getId(), event.getAttempts(), e);
                // stop the batch to preserve ordering; the next poll retries from this event
                return;
            }
        }
        if (!pending.isEmpty()) {
            log.debug("Published {} outbox events", pending.size());
        }
    }
}
