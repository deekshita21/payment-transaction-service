package dev.deekshita.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes domain events into the outbox table inside the caller's transaction. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID eventId, String aggregateType, UUID aggregateId, String eventType, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outbox.save(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + eventType, e);
        }
    }
}
