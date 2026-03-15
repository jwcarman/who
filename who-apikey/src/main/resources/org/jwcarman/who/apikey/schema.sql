CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);
