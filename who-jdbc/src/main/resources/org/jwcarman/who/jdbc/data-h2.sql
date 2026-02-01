-- Who Library Permissions (H2 Database)
-- Insert all core Who library permissions into the who_permission table
-- These permissions control access to Who library management features
-- Uses MERGE for H2 compatibility (idempotent inserts)

-- Invitation Management Permissions
MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.invitation.create', 'Create and send invitations to new users');

MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.invitation.revoke', 'Revoke pending invitations');

MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.invitation.list', 'List and view invitations');

-- Role Management Permissions
MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.role.create', 'Create new roles');

MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.role.delete', 'Delete existing roles');

-- User-Role Assignment Permissions
MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.user.role.assign', 'Assign roles to users');

MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.user.role.remove', 'Remove roles from users');

-- Role-Permission Assignment Permissions
MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.role.permission.add', 'Add permissions to roles');

MERGE INTO who_permission (id, description)
KEY (id)
VALUES ('who.role.permission.remove', 'Remove permissions from roles');
