package dev.deekshita.payments.transfer;

import dev.deekshita.payments.transfer.TransferDtos.CreateTransferRequest;
import dev.deekshita.payments.transfer.TransferDtos.PageResponse;
import dev.deekshita.payments.transfer.TransferDtos.TransferResponse;
import dev.deekshita.payments.transfer.TransferDtos.TransferResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/api/v1/transfers")
    public ResponseEntity<TransferResponse> create(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                   @Valid @RequestBody CreateTransferRequest request) {
        TransferResult result = transferService.transfer(idempotencyKey, request);
        TransferResponse body = result.transfer();
        if (result.replayed()) {
            return ResponseEntity.status(HttpStatus.OK).header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
    }

    @GetMapping("/api/v1/transfers/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return transferService.get(id);
    }

    @GetMapping("/api/v1/accounts/{accountId}/transfers")
    public PageResponse<TransferResponse> forAccount(@PathVariable UUID accountId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return transferService.forAccount(accountId, page, size);
    }
}
