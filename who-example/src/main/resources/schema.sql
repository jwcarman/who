CREATE TABLE IF NOT EXISTS task (
    id     UUID         PRIMARY KEY,
    title  VARCHAR(255) NOT NULL,
    status VARCHAR(20)  NOT NULL
);
