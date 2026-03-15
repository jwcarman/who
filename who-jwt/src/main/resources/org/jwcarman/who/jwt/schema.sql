CREATE TABLE IF NOT EXISTS who_jwt_credential (
    id      UUID         PRIMARY KEY,
    issuer  VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);
