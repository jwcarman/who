CREATE TABLE IF NOT EXISTS who_identity (
    id         UUID PRIMARY KEY,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS who_credential_identity (
    credential_id UUID PRIMARY KEY,
    identity_id   UUID NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_session (
    id             UUID PRIMARY KEY,
    identity_id    UUID NOT NULL,
    credential_id  UUID NOT NULL,
    sign_in_method VARCHAR(30) NOT NULL,
    signed_in_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    user_agent     VARCHAR(512),
    ended_at       TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS who_session_identity_idx ON who_session (identity_id);
CREATE INDEX IF NOT EXISTS who_session_last_seen_idx ON who_session (last_seen_at);
