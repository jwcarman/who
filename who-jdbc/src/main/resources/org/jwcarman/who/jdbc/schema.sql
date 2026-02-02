-- Who Library Database Schema
--
-- This schema defines all tables required by the Who library.
-- You can use this as a reference for your migration tool (Flyway, Liquibase, etc.)
-- or with Spring Boot's SQL initialization:
--
-- spring.sql.init.mode=always
-- spring.sql.init.schema-locations=classpath:org/jwcarman/who/jdbc/schema.sql
--
-- Note: You'll need to separately create the library's built-in permissions.
-- See WhoPermissions class for the list of permission IDs and descriptions.

-- User table
CREATE TABLE IF NOT EXISTS who_user (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL
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

-- Invitation table
CREATE TABLE IF NOT EXISTS who_invitation (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    role_id UUID NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    invited_by UUID NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    expires_at TIMESTAMP(9) NOT NULL,
    accepted_at TIMESTAMP(9),
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE,
    FOREIGN KEY (invited_by) REFERENCES who_user(id) ON DELETE CASCADE
);

-- Contact method table
CREATE TABLE IF NOT EXISTS who_contact_method (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    "value" VARCHAR(255) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMP(9),
    created_at TIMESTAMP(9) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES who_user(id) ON DELETE CASCADE,
    UNIQUE (user_id, type),
    UNIQUE (type, "value")
);
