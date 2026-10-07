package dev.deekshita.payments.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record CreateTransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
            @Size(max = 140) String reference) {
    }

    public record TransferResponse(
            UUID id,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String currency,
            TransferStatus status,
            String reference,
            Instant createdAt) {

        public static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                    transfer.getAmount(), transfer.getCurrency(), transfer.getStatus(), transfer.getReference(),
                    transfer.getCreatedAt());
        }
    }

    /** Stable pagination envelope (avoids serializing Spring Data's PageImpl directly). */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    /** Result of a transfer call: the transfer plus whether it was replayed from an earlier request. */
    public record TransferResult(TransferResponse transfer, boolean replayed) {
    }
}
