package dev.deekshita.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.deekshita.payments.account.AccountRepository;
import dev.deekshita.payments.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private OutboxRepository outbox;

    private static JwtRequestPostProcessor writer() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments.write"),
                new SimpleGrantedAuthority("SCOPE_payments.read"));
    }

    private static JwtRequestPostProcessor reader() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments.read"));
    }

    private UUID openAccount(String currency, String balance) throws Exception {
        String body = """
                {"ownerName":"Test Customer","currency":"%s","openingBalance":%s}
                """.formatted(currency, balance);
        MvcResult result = mvc.perform(post("/api/v1/accounts").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    private static String transferBody(UUID from, UUID to, String amount, String currency) {
        return """
                {"fromAccountId":"%s","toAccountId":"%s","amount":%s,"currency":"%s","reference":"invoice 42"}
                """.formatted(from, to, amount, currency);
    }

    private BigDecimal balanceOf(UUID id) {
        return accounts.findById(id).orElseThrow().getBalance();
    }

    @Test
    void transferMovesMoneyAndWritesOutboxEvent() throws Exception {
        UUID from = openAccount("USD", "100.00");
        UUID to = openAccount("USD", "5.00");
        long outboxBefore = outbox.count();

        mvc.perform(post("/api/v1/transfers").with(writer())
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(from, to, "25.50", "USD")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currency").value("USD"));

        assertThat(balanceOf(from)).isEqualByComparingTo("74.50");
        assertThat(balanceOf(to)).isEqualByComparingTo("30.50");
        assertThat(outbox.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void repeatedRequestWithSameKeyIsReplayedNotChargedTwice() throws Exception {
        UUID from = openAccount("USD", "100.00");
        UUID to = openAccount("USD", "0.00");
        String key = "key-" + UUID.randomUUID();
        String body = transferBody(from, to, "10", "USD");

        MvcResult first = mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = json.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "10.00", "USD")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId));

        assertThat(balanceOf(from)).isEqualByComparingTo("90.00");
        assertThat(balanceOf(to)).isEqualByComparingTo("10.00");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        UUID from = openAccount("USD", "100.00");
        UUID to = openAccount("USD", "0.00");
        String key = "key-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "10.00", "USD")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "11.00", "USD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void insufficientFundsIsRejectedAndBalancesUnchanged() throws Exception {
        UUID from = openAccount("USD", "20.00");
        UUID to = openAccount("USD", "0.00");

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "20.01", "USD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(from)).isEqualByComparingTo("20.00");
        assertThat(balanceOf(to)).isEqualByComparingTo("0.00");
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        UUID from = openAccount("USD", "50.00");
        UUID to = openAccount("EUR", "0.00");

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "5.00", "USD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void frozenAccountCannotSendMoney() throws Exception {
        UUID from = openAccount("USD", "50.00");
        UUID to = openAccount("USD", "0.00");
        mvc.perform(post("/api/v1/accounts/{id}/freeze", from).with(writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "5.00", "USD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void invalidRequestsReturnProblemDetails() throws Exception {
        UUID from = openAccount("USD", "50.00");
        UUID to = openAccount("USD", "0.00");

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "0", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.amount").exists());

        mvc.perform(post("/api/v1/transfers").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "1.00", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, from, "1.00", "USD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()).with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void securityRequiresTokenAndCorrectScope() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/accounts").with(reader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"X\",\"currency\":\"USD\",\"openingBalance\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void accountHistoryIsPaged() throws Exception {
        UUID from = openAccount("USD", "100.00");
        UUID to = openAccount("USD", "0.00");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/transfers").with(writer()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(transferBody(from, to, "1.00", "USD")))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get("/api/v1/accounts/{id}/transfers", to).param("page", "0").param("size", "2").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void correlationIdIsEchoedBack() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }
}
