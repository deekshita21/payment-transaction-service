package dev.deekshita.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deekshita.payments.account.AccountDtos.CreateAccountRequest;
import dev.deekshita.payments.account.AccountRepository;
import dev.deekshita.payments.account.AccountService;
import dev.deekshita.payments.transfer.TransferDtos.CreateTransferRequest;
import dev.deekshita.payments.transfer.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fires transfers in both directions between two accounts from several threads at once and
 * checks that no money is created or lost and that no deadlock occurs.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentTransferTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accounts;

    @Test
    void concurrentOppositeTransfersConserveTotalBalance() throws Exception {
        UUID a = accountService.open(new CreateAccountRequest("Alice Example", "USD", new BigDecimal("500.00"))).id();
        UUID b = accountService.open(new CreateAccountRequest("Bob Example", "USD", new BigDecimal("500.00"))).id();

        int tasks = 80;
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            boolean forward = i % 2 == 0;
            work.add(() -> {
                try {
                    transferService.transfer("conc-" + UUID.randomUUID(),
                            new CreateTransferRequest(forward ? a : b, forward ? b : a,
                                    new BigDecimal("7.25"), "USD", null));
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        BigDecimal balanceA = accounts.findById(a).orElseThrow().getBalance();
        BigDecimal balanceB = accounts.findById(b).orElseThrow().getBalance();
        assertThat(balanceA.add(balanceB)).isEqualByComparingTo("1000.00");
        assertThat(balanceA).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balanceB).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(rejected.get()).isZero();
    }
}
