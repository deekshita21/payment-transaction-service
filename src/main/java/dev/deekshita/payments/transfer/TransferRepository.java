package dev.deekshita.payments.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    @Query("select t from Transfer t where t.fromAccountId = :accountId or t.toAccountId = :accountId")
    Page<Transfer> findByAccount(@Param("accountId") UUID accountId, Pageable pageable);
}
