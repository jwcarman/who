CREATE TABLE IF NOT EXISTS who_email_credential (
    id      UUID         PRIMARY KEY,
    address VARCHAR(320) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_email_link_token (
    credential_id UUID                     PRIMARY KEY REFERENCES who_email_credential (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64)              NOT NULL UNIQUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    code_hash     VARCHAR(64)              NOT NULL,
    code_attempts INTEGER                  NOT NULL
);

CREATE TABLE IF NOT EXISTS who_email_address_request (
    id                     UUID                     PRIMARY KEY,
    identity_id            UUID                     NOT NULL UNIQUE REFERENCES who_identity (id) ON DELETE CASCADE,
    address                VARCHAR(320)             NOT NULL,
    replaces_credential_id UUID,
    token_hash             VARCHAR(64)              NOT NULL UNIQUE,
    code_hash              VARCHAR(64)              NOT NULL,
    code_attempts          INTEGER                  NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_who_email_address_request_address ON who_email_address_request (address);
