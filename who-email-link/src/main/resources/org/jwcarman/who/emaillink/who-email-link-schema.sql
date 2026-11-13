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
