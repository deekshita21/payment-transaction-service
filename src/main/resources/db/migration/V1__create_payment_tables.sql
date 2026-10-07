-- Schema is written to run on PostgreSQL 14+ and on H2 (PostgreSQL mode) for fast tests.

CREATE TABLE accounts (
    id          UUID           PRIMARY KEY,
    owner_name  VARCHAR(120)   NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    balance     NUMERIC(19, 2) NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE transfers (
    id               UUID           PRIMARY KEY,
    idempotency_key  VARCHAR(64)    NOT NULL,
    request_hash     VARCHAR(64)    NOT NULL,
    from_account_id  UUID           NOT NULL REFERENCES accounts (id),
    to_account_id    UUID           NOT NULL REFERENCES accounts (id),
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    reference        VARCHAR(140),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_transfers_from_account ON transfers (from_account_id, created_at);
CREATE INDEX idx_transfers_to_account ON transfers (to_account_id, created_at);

CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,
    aggregate_type  VARCHAR(60)   NOT NULL,
    aggregate_id    UUID          NOT NULL,
    event_type      VARCHAR(60)   NOT NULL,
    payload         VARCHAR(4000) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    attempts        INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at, created_at);
