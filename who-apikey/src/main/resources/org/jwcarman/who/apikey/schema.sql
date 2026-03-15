CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);
