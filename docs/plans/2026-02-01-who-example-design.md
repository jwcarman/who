# who-example Application Design

**Date:** 2026-02-01
**Status:** Approved
**Purpose:** Functional REST API example demonstrating the who library's authentication and RBAC features

## Overview

The who-example module is a Spring Boot application that demonstrates real-world usage of the who library. It implements a Task/Todo API with JWT authentication, internal identity mapping, and role-based access control.

**Key Features:**
- Embedded Spring Authorization Server for zero-config startup
- Two-tier RBAC (USER and ADMIN roles)
- User-specific task management
- Admin endpoints for viewing all tasks and managing users

## Architecture

### Module Structure

```
who-example/
├── pom.xml                          (Spring Boot app, depends on who-spring-boot-starter)
├── src/main/java/
│   └── org/jwcarman/who/example/
│       ├── WhoExampleApplication.java
│       ├── config/
│       │   └── SecurityConfig.java  (Embedded Auth Server + Resource Server)
│       ├── domain/
│       │   └── Task.java
│       ├── repository/
│       │   └── TaskRepository.java
│       └── controller/
│           ├── TaskController.java  (User endpoints)
│           └── AdminController.java (Admin endpoints)
└── src/main/resources/
    ├── application.yml
    └── data.sql                     (H2 bootstrap: users, roles, sample tasks)
```

### Key Dependencies

- `who-spring-boot-starter` - Brings in all who features
- `spring-boot-starter-oauth2-authorization-server` - Embedded auth server
- `spring-boot-starter-data-jpa` with H2 - In-memory database

### Runtime Behavior

Single Spring Boot application runs both:
1. **Authorization Server** - Issues JWTs at `/oauth2/token`
2. **Resource Server** - Task API endpoints that validate JWTs

Developers run `mvn spring-boot:run` and everything works immediately - no external dependencies or configuration required.

## Authentication & Security

### Embedded Authorization Server

**Pre-configured OAuth2 Client:**
- Client ID: `demo-client`
- Client Secret: `secret`
- Grants: `password`, `client_credentials`

**Pre-configured Users:**
- `alice` (password: `password`) - USER role
- `bob` (password: `password`) - USER role
- `admin` (password: `password`) - ADMIN role

**JWT Configuration:**
- Issuer: `http://localhost:8080`
- Standard OAuth2 claims (`sub`, `iss`, `exp`, etc.)
- The who library automatically maps `(issuer, subject)` to internal user IDs

### Getting a Token

```bash
# Get token for alice
curl -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=password"

# Response includes access_token to use in Authorization header
```

## Domain Model

### Task Entity

```java
@Entity
class Task {
    UUID id;
    UUID userId;        // Owner (from who's internal user ID)
    String title;
    String description;
    TaskStatus status;  // TODO, IN_PROGRESS, DONE
    Instant createdAt;
    Instant updatedAt;
}
```

Tasks are owned by users via the internal user ID from the who library. Users can only access their own tasks unless they have admin permissions.

## API Endpoints

### User Endpoints (`/api/tasks`)

**List my tasks:**
- `GET /api/tasks`
- Permission: `task.own.read`
- Returns tasks owned by authenticated user

**Create task:**
- `POST /api/tasks`
- Permission: `task.own.write`
- Request body: `{"title": "...", "description": "...", "status": "TODO"}`

**Get my task:**
- `GET /api/tasks/{id}`
- Permission: `task.own.read`
- Verifies task ownership before returning

**Update my task:**
- `PUT /api/tasks/{id}`
- Permission: `task.own.write`
- Verifies ownership before updating

**Delete my task:**
- `DELETE /api/tasks/{id}`
- Permission: `task.own.write`
- Verifies ownership before deleting

### Admin Endpoints (`/api/admin`)

**List all tasks:**
- `GET /api/admin/tasks`
- Permission: `task.all.read`
- Returns tasks for all users

**Get any task:**
- `GET /api/admin/tasks/{id}`
- Permission: `task.all.read`
- No ownership check required

**List users:**
- `GET /api/admin/users`
- Permission: `user.manage`
- Returns all users in the system

## RBAC Model

### Roles

**USER:**
- `task.own.read` - Read own tasks
- `task.own.write` - Create/update/delete own tasks

**ADMIN:**
- All USER permissions
- `task.all.read` - Read all tasks
- `task.all.write` - Modify all tasks
- `user.manage` - View and manage users

### Permission Enforcement

Uses Spring Security's `@PreAuthorize` annotations:
```java
@PreAuthorize("hasAuthority('task.own.read')")
public List<Task> getMyTasks(@AuthenticationPrincipal WhoPrincipal principal)
```

Ownership verification in service layer:
```java
if (!task.getUserId().equals(principal.userId())) {
    throw new AccessDeniedException("Not your task");
}
```

## Bootstrap Data

The `data.sql` file populates H2 database on startup:

**Users:**
- alice, bob, admin in `who_user` table
- External identities linking OAuth2 subjects to internal user IDs
- Passwords hashed and stored (for embedded auth server)

**Roles & Permissions:**
- USER and ADMIN roles in `who_role` table
- Permissions in `who_role_permission` table
- User-role assignments in `who_user_role` table

**Sample Tasks:**
- Several tasks owned by alice
- Several tasks owned by bob
- Demonstrates user-specific data isolation

## Usage Example

```bash
# 1. Start the application
mvn spring-boot:run

# 2. Get a token for alice
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -d "grant_type=password" \
  -d "username=alice" \
  -d "password=password" | jq -r .access_token)

# 3. List alice's tasks
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/tasks

# 4. Create a new task
curl -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/tasks \
  -d '{"title":"Buy milk","description":"From the store","status":"TODO"}'

# 5. Get admin token
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -d "grant_type=password" \
  -d "username=admin" \
  -d "password=password" | jq -r .access_token)

# 6. Admin views all tasks
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/admin/tasks
```

## Future Extensions

This initial version focuses on authentication and basic RBAC. Future iterations could add:

1. **User Preferences** - Demonstrate namespaced JSON preferences (task filters, view settings)
2. **Identity Linking** - Show how to link multiple OAuth2 identities to one user
3. **Role Management UI** - Admin endpoints for creating roles and assigning permissions
4. **Multi-issuer Support** - Configure multiple OAuth2 providers (Google, GitHub, etc.)
5. **Actuator Integration** - Health checks and metrics

## "who" Library Conventions

### Standard Permissions

The who library defines its own permissions for management endpoints (when who-web is included):

- `who.role.assign` - Assign roles to users
- `who.identity.link` - Link external identities
- `who.user.manage` - Manage users

### Recommended Application Patterns

While the who library doesn't enforce permission naming patterns, this example demonstrates recommended conventions:

**Pattern:** `resource.scope.action`
- `task.own.read` - Read own tasks
- `task.own.write` - Write own tasks
- `task.all.read` - Read all tasks
- `task.all.write` - Write all tasks
- `user.manage` - Manage users

Applications are free to use any permission strings that work for their domain.

## Success Criteria

The who-example application is successful if:

1. ✅ Developers can run `mvn spring-boot:run` without any configuration
2. ✅ README provides clear curl examples that work immediately
3. ✅ Demonstrates JWT authentication with who's identity mapping
4. ✅ Shows two-tier RBAC with clear permission boundaries
5. ✅ Code is simple and easy to understand (not over-engineered)
6. ✅ Serves as a starting point for developers integrating who into their apps
