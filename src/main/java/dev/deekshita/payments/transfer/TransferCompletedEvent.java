package dev.deekshita.payments.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Integration event published (via the outbox) after a transfer commits. */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {

    public static final String TYPE = "TransferCompleted";

    static TransferCompletedEvent of(Transfer transfer) {
        return new TransferCompletedEvent(UUID.randomUUID(), transfer.getId(), transfer.getFromAccountId(),
                transfer.getToAccountId(), transfer.getAmount(), transfer.getCurrency(), transfer.getCreatedAt());
    }
}
