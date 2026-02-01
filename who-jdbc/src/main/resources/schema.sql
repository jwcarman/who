-- User table
CREATE TABLE IF NOT EXISTS who_user (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Role table
CREATE TABLE IF NOT EXISTS who_role (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

-- Permission table
CREATE TABLE IF NOT EXISTS who_permission (
    id VARCHAR(255) PRIMARY KEY,
    description TEXT
);

-- User-Role assignment table
CREATE TABLE IF NOT EXISTS who_user_role (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES who_user(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);

-- Role-Permission assignment table
CREATE TABLE IF NOT EXISTS who_role_permission (
    role_id UUID NOT NULL,
    permission_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES who_permission(id) ON DELETE CASCADE
);

-- External identity table
CREATE TABLE IF NOT EXISTS who_external_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject),
    FOREIGN KEY (user_id) REFERENCES who_user(id) ON DELETE CASCADE
);

-- User preference table
CREATE TABLE IF NOT EXISTS who_user_preference (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    data TEXT NOT NULL,
    UNIQUE (user_id, namespace),
    FOREIGN KEY (user_id) REFERENCES who_user(id) ON DELETE CASCADE
);
