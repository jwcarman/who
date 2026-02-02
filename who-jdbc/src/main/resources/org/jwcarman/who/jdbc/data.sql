-- Who Library Permissions (PostgreSQL/generic syntax)
-- Insert all core Who library permissions into the who_permission table
-- These permissions control access to Who library management features
--
-- Note: For H2 database, use data-h2.sql instead (uses MERGE syntax)

-- Invitation Management Permissions
INSERT INTO who_permission (id, description)
VALUES ('who.invitation.create', 'Create and send invitations to new users')
ON CONFLICT (id) DO NOTHING;

INSERT INTO who_permission (id, description)
VALUES ('who.invitation.revoke', 'Revoke pending invitations')
ON CONFLICT (id) DO NOTHING;

INSERT INTO who_permission (id, description)
VALUES ('who.invitation.list', 'List and view invitations')
ON CONFLICT (id) DO NOTHING;

-- Role Management Permissions
INSERT INTO who_permission (id, description)
VALUES ('who.role.create', 'Create new roles')
ON CONFLICT (id) DO NOTHING;

INSERT INTO who_permission (id, description)
VALUES ('who.role.delete', 'Delete existing roles')
ON CONFLICT (id) DO NOTHING;

-- User-Role Assignment Permissions
INSERT INTO who_permission (id, description)
VALUES ('who.user.role.assign', 'Assign roles to users')
ON CONFLICT (id) DO NOTHING;

INSERT INTO who_permission (id, description)
VALUES ('who.user.role.remove', 'Remove roles from users')
ON CONFLICT (id) DO NOTHING;

-- Role-Permission Assignment Permissions
INSERT INTO who_permission (id, description)
VALUES ('who.role.permission.add', 'Add permissions to roles')
ON CONFLICT (id) DO NOTHING;

INSERT INTO who_permission (id, description)
VALUES ('who.role.permission.remove', 'Remove permissions from roles')
ON CONFLICT (id) DO NOTHING;
