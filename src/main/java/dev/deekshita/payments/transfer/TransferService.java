package dev.deekshita.payments.transfer;

import dev.deekshita.payments.BusinessException;
import dev.deekshita.payments.account.Account;
import dev.deekshita.payments.account.AccountRepository;
import dev.deekshita.payments.outbox.OutboxWriter;
import dev.deekshita.payments.transfer.TransferDtos.CreateTransferRequest;
import dev.deekshita.payments.transfer.TransferDtos.PageResponse;
import dev.deekshita.payments.transfer.TransferDtos.TransferResponse;
import dev.deekshita.payments.transfer.TransferDtos.TransferResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves money between two accounts.
 *
 * <ul>
 *   <li><b>Idempotency:</b> every request carries an Idempotency-Key. A retried request with the
 *       same key and payload returns the original transfer; the same key with a different payload
 *       is rejected with 409.</li>
 *   <li><b>Consistency:</b> both account rows are locked (SELECT ... FOR UPDATE) in a fixed order to
 *       avoid deadlocks, and the debit, credit, transfer row and outbox event commit atomically.</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MAX_KEY_LENGTH = 64;

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public TransferService(AccountRepository accounts, TransferRepository transfers, OutboxWriter outboxWriter,
                           TransactionTemplate transactionTemplate) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
    }

    public TransferResult transfer(String idempotencyKey, CreateTransferRequest request) {
        validateKey(idempotencyKey);
        BigDecimal amount = normalize(request.amount());
        String requestHash = hash(request, amount);

        Optional<TransferResult> replay = findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            Transfer created = transactionTemplate.execute(status -> execute(idempotencyKey, requestHash, request, amount));
            return new TransferResult(TransferResponse.from(created), false);
        } catch (DataIntegrityViolationException race) {
            // Two requests with the same key raced; the unique constraint let exactly one win.
            return findReplay(idempotencyKey, requestHash).orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID id) {
        return transfers.findById(id)
                .map(TransferResponse::from)
                .orElseThrow(() -> BusinessException.notFound("Transfer", id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> forAccount(UUID accountId, int page, int size) {
        if (!accounts.existsById(accountId)) {
            throw BusinessException.notFound("Account", accountId);
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(transfers.findByAccount(accountId, pageable).map(TransferResponse::from));
    }

    private Transfer execute(String key, String requestHash, CreateTransferRequest request, BigDecimal amount) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw BusinessException.unprocessable("SAME_ACCOUNT", "Source and destination accounts must differ");
        }

        // Lock in a deterministic order so two opposite transfers cannot deadlock.
        UUID first = request.fromAccountId().compareTo(request.toAccountId()) < 0
                ? request.fromAccountId() : request.toAccountId();
        UUID second = first.equals(request.fromAccountId()) ? request.toAccountId() : request.fromAccountId();
        Account firstLocked = lock(first);
        Account secondLocked = lock(second);
        Account from = firstLocked.getId().equals(request.fromAccountId()) ? firstLocked : secondLocked;
        Account to = from == firstLocked ? secondLocked : firstLocked;

        if (!from.isActive() || !to.isActive()) {
            throw BusinessException.unprocessable("ACCOUNT_NOT_ACTIVE", "Both accounts must be active");
        }
        if (!from.getCurrency().equals(request.currency()) || !to.getCurrency().equals(request.currency())) {
            throw BusinessException.unprocessable("CURRENCY_MISMATCH",
                    "Transfer currency must match both account currencies");
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw BusinessException.unprocessable("INSUFFICIENT_FUNDS", "Source account has insufficient funds");
        }

        from.debit(amount);
        to.credit(amount);
        Transfer transfer = transfers.saveAndFlush(new Transfer(key, requestHash, from.getId(), to.getId(), amount,
                request.currency(), request.reference()));

        TransferCompletedEvent event = TransferCompletedEvent.of(transfer);
        outboxWriter.write(event.eventId(), "Transfer", transfer.getId(), TransferCompletedEvent.TYPE, event);

        log.info("Transfer completed id={} amount={} {}", transfer.getId(), amount, request.currency());
        return transfer;
    }

    private Account lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> BusinessException.notFound("Account", id));
    }

    private Optional<TransferResult> findReplay(String key, String requestHash) {
        return transfers.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request body");
            }
            log.info("Replaying transfer id={} for repeated idempotency key", existing.getId());
            return new TransferResult(TransferResponse.from(existing), true);
        });
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
    }

    private static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    static String hash(CreateTransferRequest request, BigDecimal amount) {
        String canonical = String.join("|",
                request.fromAccountId().toString(),
                request.toAccountId().toString(),
                amount.toPlainString(),
                request.currency(),
                request.reference() == null ? "" : request.reference());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
