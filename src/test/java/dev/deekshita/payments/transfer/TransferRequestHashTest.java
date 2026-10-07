package dev.deekshita.payments.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deekshita.payments.transfer.TransferDtos.CreateTransferRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferRequestHashTest {

    private final UUID from = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID to = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void equivalentAmountsProduceTheSameHash() {
        CreateTransferRequest a = new CreateTransferRequest(from, to, new BigDecimal("10"), "USD", null);
        CreateTransferRequest b = new CreateTransferRequest(from, to, new BigDecimal("10.00"), "USD", null);

        assertThat(TransferService.hash(a, new BigDecimal("10.00")))
                .isEqualTo(TransferService.hash(b, new BigDecimal("10.00")));
    }

    @Test
    void differentPayloadsProduceDifferentHashes() {
        CreateTransferRequest a = new CreateTransferRequest(from, to, new BigDecimal("10.00"), "USD", "rent");
        CreateTransferRequest b = new CreateTransferRequest(from, to, new BigDecimal("10.00"), "USD", "food");

        assertThat(TransferService.hash(a, a.amount())).isNotEqualTo(TransferService.hash(b, b.amount()));
    }
}
