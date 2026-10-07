package dev.deekshita.payments.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal openingBalance) {
    }

    public record AccountResponse(
            UUID id,
            String ownerName,
            String currency,
            BigDecimal balance,
            AccountStatus status,
            Instant createdAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getOwnerName(), account.getCurrency(),
                    account.getBalance(), account.getStatus(), account.getCreatedAt());
        }
    }
}
