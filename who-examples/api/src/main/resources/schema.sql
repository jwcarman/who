-- The app's own table. who's tables come from who.initialize-schema.
CREATE TABLE IF NOT EXISTS task (
    id         UUID         PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
