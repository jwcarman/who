CREATE TABLE IF NOT EXISTS who_enrollment_token (
    id          UUID         PRIMARY KEY,
    identity_id UUID         NOT NULL,
    token_value VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    redeemed_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);
