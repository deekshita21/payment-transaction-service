package dev.deekshita.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deekshita.payments.account.AccountDtos.CreateAccountRequest;
import dev.deekshita.payments.account.AccountService;
import dev.deekshita.payments.outbox.OutboxRepository;
import dev.deekshita.payments.transfer.TransferDtos.CreateTransferRequest;
import dev.deekshita.payments.transfer.TransferService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs Flyway migrations and Hibernate schema validation against a real PostgreSQL 16
 * container. Skipped automatically when Docker is not available.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxRepository outbox;

    @Test
    void transferCommitsOnPostgres() {
        UUID a = accountService.open(new CreateAccountRequest("Carol Example", "USD", new BigDecimal("40.00"))).id();
        UUID b = accountService.open(new CreateAccountRequest("Dan Example", "USD", new BigDecimal("0.00"))).id();

        var result = transferService.transfer("pg-" + UUID.randomUUID(),
                new CreateTransferRequest(a, b, new BigDecimal("15.00"), "USD", "pg test"));

        assertThat(result.replayed()).isFalse();
        assertThat(accountService.get(a).balance()).isEqualByComparingTo("25.00");
        assertThat(accountService.get(b).balance()).isEqualByComparingTo("15.00");
        assertThat(outbox.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(1);
    }
}
