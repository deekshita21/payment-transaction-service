package dev.deekshita.payments.account;

import dev.deekshita.payments.BusinessException;
import dev.deekshita.payments.account.AccountDtos.AccountResponse;
import dev.deekshita.payments.account.AccountDtos.CreateAccountRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse open(CreateAccountRequest request) {
        Account account = accounts.save(
                new Account(request.ownerName().trim(), request.currency(), request.openingBalance()));
        log.info("Opened account id={} currency={}", account.getId(), account.getCurrency());
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        return accounts.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> BusinessException.notFound("Account", id));
    }

    @Transactional
    public AccountResponse freeze(UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> BusinessException.notFound("Account", id));
        account.freeze();
        log.info("Froze account id={}", id);
        return AccountResponse.from(account);
    }
}
