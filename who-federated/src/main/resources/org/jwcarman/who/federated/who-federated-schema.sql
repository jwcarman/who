ALTER TABLE IF EXISTS who_jwt_credential RENAME TO who_federated_credential;

CREATE TABLE IF NOT EXISTS who_federated_credential (
    id      UUID         PRIMARY KEY,
    issuer  VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);
