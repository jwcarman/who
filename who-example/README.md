# who-example - Task API Demo

A functional REST API demonstrating the [who library](../) authentication and RBAC features.

## Features

- **Zero-config startup** - Just run and it works
- **Embedded OAuth2 server** - Get real JWTs from `http://localhost:8080/oauth2/token`
- **Task API** - User-specific CRUD operations
- **Admin endpoints** - View all tasks and users
- **Two-tier RBAC** - USER and ADMIN roles with different permissions

## Quick Start

### 1. Start the Application

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080` with both:
- Authorization Server (issues JWTs)
- Resource Server (Task API)

### 2. Get a Token

**For alice (USER role):**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=password" \
  | jq -r .access_token)
```

**For admin (ADMIN role):**

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -d "grant_type=password" \
  -d "username=admin" \
  -d "password=password" \
  | jq -r .access_token)
```

**Available users:**
- `alice` / `password` - USER role
- `bob` / `password` - USER role
- `admin` / `password` - ADMIN role

### 3. Use the API

**List my tasks:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/tasks
```

**Create a task:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d '{
    "title": "Buy groceries",
    "description": "Milk, eggs, bread",
    "status": "TODO"
  }'
```

**Get a specific task:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/tasks/{task-id}
```

**Update a task:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X PUT http://localhost:8080/api/tasks/{task-id} \
  -d '{
    "title": "Buy groceries",
    "description": "Milk, eggs, bread, cheese",
    "status": "IN_PROGRESS"
  }'
```

**Delete a task:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -X DELETE http://localhost:8080/api/tasks/{task-id}
```

**Admin - View all tasks:**

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/admin/tasks
```

**Admin - View all users:**

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/admin/users
```

## API Endpoints

### User Endpoints (`/api/tasks`)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| GET | `/api/tasks` | `task.own.read` | List my tasks |
| POST | `/api/tasks` | `task.own.write` | Create a task |
| GET | `/api/tasks/{id}` | `task.own.read` | Get my task |
| PUT | `/api/tasks/{id}` | `task.own.write` | Update my task |
| DELETE | `/api/tasks/{id}` | `task.own.write` | Delete my task |

### Admin Endpoints (`/api/admin`)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| GET | `/api/admin/tasks` | `task.all.read` | List all tasks |
| GET | `/api/admin/tasks/{id}` | `task.all.read` | Get any task |
| GET | `/api/admin/users` | `user.manage` | List all users |

## RBAC Model

**USER Role:**
- `task.own.read` - Read own tasks
- `task.own.write` - Create/update/delete own tasks

**ADMIN Role:**
- All USER permissions
- `task.all.read` - Read all tasks
- `task.all.write` - Modify all tasks
- `user.manage` - View and manage users

## How It Works

### Authentication Flow

1. **Get JWT from embedded Authorization Server** - POST to `/oauth2/token` with username/password
2. **who library maps identity** - Extracts `(issuer, subject)` from JWT, looks up internal user ID
3. **Permissions loaded** - Roles and permissions for that user become Spring Security authorities
4. **API enforces access** - `@PreAuthorize` annotations check permissions

### Identity Mapping

The `data.sql` bootstrap script creates:
- Users in `who_user` table (alice, bob, admin)
- External identities in `who_external_identity` linking OAuth2 subjects to internal user IDs
- Roles and permissions in `who_role` and `who_role_permission` tables

When a JWT arrives:
```
JWT subject: "alice", issuer: "http://localhost:8080"
  ↓
who library: "This maps to user ID 550e8400-e29b-41d4-a716-446655440001"
  ↓
Load permissions: ["task.own.read", "task.own.write"]
  ↓
Spring Security: Available as authorities for @PreAuthorize checks
```

## Database

**H2 Console:** http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:whoexample`
- Username: `sa`
- Password: (empty)

## Architecture

```
┌─────────────────────────────────────────┐
│     who-example Application             │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  Authorization Server           │   │
│  │  (Spring Authorization Server)  │   │
│  │  - Issues JWTs                  │   │
│  │  - /oauth2/token                │   │
│  └─────────────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  Resource Server (Task API)     │   │
│  │  - Validates JWTs               │   │
│  │  - Uses who library             │   │
│  │  - /api/tasks, /api/admin       │   │
│  └─────────────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  who Library                    │   │
│  │  - Identity mapping             │   │
│  │  - RBAC permissions             │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

## License

Licensed under the [Apache License, Version 2.0](../LICENSE)
