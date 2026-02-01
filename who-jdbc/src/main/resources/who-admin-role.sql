-- Who Admin Role Setup
-- Creates a 'who-admin' role and grants all Who library permissions
-- This role can fully manage the Who library features (users, roles, permissions, invitations)

-- Create the who-admin role
INSERT INTO who_role (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'who-admin')
ON CONFLICT (id) DO NOTHING;

-- Grant all Who library permissions to the who-admin role
INSERT INTO who_role_permission (role_id, permission_id)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'who.invitation.create'),
    ('00000000-0000-0000-0000-000000000001', 'who.invitation.revoke'),
    ('00000000-0000-0000-0000-000000000001', 'who.invitation.list'),
    ('00000000-0000-0000-0000-000000000001', 'who.role.create'),
    ('00000000-0000-0000-0000-000000000001', 'who.role.delete'),
    ('00000000-0000-0000-0000-000000000001', 'who.user.role.assign'),
    ('00000000-0000-0000-0000-000000000001', 'who.user.role.remove'),
    ('00000000-0000-0000-0000-000000000001', 'who.role.permission.add'),
    ('00000000-0000-0000-0000-000000000001', 'who.role.permission.remove')
ON CONFLICT (role_id, permission_id) DO NOTHING;
