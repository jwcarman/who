-- Bootstrap data for who-example application
-- This runs after Hibernate creates the schema

-- Create users (alice, bob, admin)
-- UUIDs are time-based for consistency
INSERT INTO who_user (id, status, created_at, updated_at) VALUES
('550e8400-e29b-41d4-a716-446655440001', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('550e8400-e29b-41d4-a716-446655440002', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('550e8400-e29b-41d4-a716-446655440003', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Create external identities mapping OAuth2 subjects to users
-- Issuer is http://localhost:8080 (our embedded auth server)
-- Subjects match the usernames from UserDetailsService
INSERT INTO who_external_identity (id, user_id, issuer, subject, first_seen_at, last_seen_at) VALUES
('650e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440001', 'http://localhost:8080', 'alice', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('650e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440002', 'http://localhost:8080', 'bob', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('650e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440003', 'http://localhost:8080', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Create roles
INSERT INTO who_role (id, name) VALUES
('750e8400-e29b-41d4-a716-446655440001', 'USER'),
('750e8400-e29b-41d4-a716-446655440002', 'ADMIN');

-- Assign permissions to USER role
INSERT INTO who_role_permission (role_id, permission) VALUES
('750e8400-e29b-41d4-a716-446655440001', 'task.own.read'),
('750e8400-e29b-41d4-a716-446655440001', 'task.own.write');

-- Assign permissions to ADMIN role (includes all USER permissions plus admin ones)
INSERT INTO who_role_permission (role_id, permission) VALUES
('750e8400-e29b-41d4-a716-446655440002', 'task.own.read'),
('750e8400-e29b-41d4-a716-446655440002', 'task.own.write'),
('750e8400-e29b-41d4-a716-446655440002', 'task.all.read'),
('750e8400-e29b-41d4-a716-446655440002', 'task.all.write'),
('750e8400-e29b-41d4-a716-446655440002', 'user.manage');

-- Assign roles to users
INSERT INTO who_user_role (user_id, role_id) VALUES
('550e8400-e29b-41d4-a716-446655440001', '750e8400-e29b-41d4-a716-446655440001'),  -- alice -> USER
('550e8400-e29b-41d4-a716-446655440002', '750e8400-e29b-41d4-a716-446655440001'),  -- bob -> USER
('550e8400-e29b-41d4-a716-446655440003', '750e8400-e29b-41d4-a716-446655440002');  -- admin -> ADMIN

-- Create sample tasks
INSERT INTO task (id, user_id, title, description, status, created_at, updated_at) VALUES
('850e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440001', 'Buy milk', 'Get 2% milk from the store', 'TODO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('850e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440001', 'Write report', 'Quarterly sales report', 'IN_PROGRESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('850e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440001', 'Fix bug #123', 'Null pointer in user service', 'DONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('850e8400-e29b-41d4-a716-446655440004', '550e8400-e29b-41d4-a716-446655440002', 'Review PR', 'Code review for auth feature', 'TODO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('850e8400-e29b-41d4-a716-446655440005', '550e8400-e29b-41d4-a716-446655440002', 'Update docs', 'Add API documentation', 'IN_PROGRESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
