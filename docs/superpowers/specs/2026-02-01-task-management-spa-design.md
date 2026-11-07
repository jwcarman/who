# Task Management SPA Design

## Goal

Create a single-page application (SPA) that demonstrates the Who library's permission-based access control with a working task management UI using client-side OAuth2 authentication.

## Architecture

**Technology Stack:**
- Pure HTML/CSS/JavaScript (no frameworks, no build tools)
- OAuth2 Authorization Code Flow with PKCE
- Web Crypto API for PKCE implementation
- Fetch API for REST calls
- sessionStorage for token management

**Component Overview:**
- Single HTML file (`tasks.html`) containing all code
- Calls existing `/api/tasks` REST API
- Uses existing OAuth2 authorization server
- No backend changes required

## OAuth2 + PKCE Implementation

**PKCE Flow:**

PKCE (Proof Key for Code Exchange) protects the authorization code flow in public clients where secrets cannot be kept secure.

1. **Generate code verifier:**
   - Random 32-byte value
   - Base64URL encoded
   - Stored in sessionStorage

2. **Generate code challenge:**
   - SHA-256 hash of code verifier
   - Base64URL encoded
   - Sent to authorization server

3. **Authorization request:**
   ```
   GET /oauth2/authorize?
     response_type=code&
     client_id=demo-client&
     redirect_uri=http://localhost:8080/tasks.html&
     code_challenge=<hash>&
     code_challenge_method=S256&
     scope=read write
   ```

4. **Token exchange:**
   ```
   POST /oauth2/token
   Content-Type: application/x-www-form-urlencoded

   grant_type=authorization_code&
   code=<authorization_code>&
   redirect_uri=http://localhost:8080/tasks.html&
   client_id=demo-client&
   code_verifier=<original_verifier>
   ```

5. **Token storage:**
   - access_token → sessionStorage
   - refresh_token → sessionStorage
   - expires_in → calculate expiry timestamp

**Token Refresh:**

When access token expires:
```
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&
refresh_token=<refresh_token>&
client_id=demo-client
```

## User Flow

1. **Initial load:** User visits `/tasks.html`
2. **Authentication check:** Check sessionStorage for valid token
3. **Login redirect:** If no token, redirect to OAuth2 authorize endpoint
4. **Callback handling:** Parse authorization code from URL parameters
5. **Token exchange:** Exchange code for access/refresh tokens
6. **Store tokens:** Save to sessionStorage, remove code from URL
7. **Load tasks:** Fetch tasks from `/api/tasks`
8. **Render UI:** Display task list with CRUD operations
9. **Token management:** Auto-refresh on expiry, logout on refresh failure

## UI Design

**Layout:**
```
+------------------------------------------+
| Task Manager          alice    [Logout] |
+------------------------------------------+
| [+ Add Task]                             |
+------------------------------------------+
| □ Task Title 1                    [Edit] |
|   Description text...            [Delete]|
+------------------------------------------+
| □ Task Title 2                    [Edit] |
|   Description text...            [Delete]|
+------------------------------------------+
```

**Components:**

1. **Header:**
   - Application title
   - Logged-in username (from JWT sub claim)
   - Logout button

2. **Add Task Form:**
   - Title input
   - Description textarea
   - Status dropdown (TODO, IN_PROGRESS, DONE)
   - Submit button

3. **Task List:**
   - Task cards/rows
   - Checkbox for status toggle
   - Title and description
   - Edit/Delete buttons
   - Empty state message

4. **Task Edit Form:**
   - Inline or modal
   - Same fields as add form
   - Save/Cancel buttons

**State Management:**

- Tasks array in memory
- Re-render on changes (simple DOM manipulation)
- Optimistic updates with rollback on error

## API Integration

**Authentication:**
All API calls include `Authorization: Bearer <access_token>` header.

**Endpoints:**

- `GET /api/tasks` - List current user's tasks
- `POST /api/tasks` - Create new task
- `PUT /api/tasks/{id}` - Update task
- `DELETE /api/tasks/{id}` - Delete task

**Error Handling:**

- **401 Unauthorized:** Token expired/invalid → Try refresh, then logout
- **403 Forbidden:** Insufficient permissions → Show error message
- **404 Not Found:** Task not found → Remove from UI
- **Network errors:** Show retry option

## Security Considerations

**Token Storage:**
- sessionStorage (cleared on tab close)
- Not localStorage (persists across sessions)
- Not accessible to other origins

**XSS Mitigation:**
- No eval() or innerHTML with user content
- Sanitize user input before rendering
- Content Security Policy headers

**CSRF Protection:**
- Not needed (no cookies, stateless bearer tokens)
- Already disabled in SecurityConfig

## Demo Characteristics

**Test Users:**
- alice/bob: Can create/read/update/delete own tasks (task.own.read, task.own.write)
- admin: Can manage own tasks + has user.manage permission

**Demonstrates:**
- OAuth2 Authorization Code Flow with PKCE
- JWT bearer token authentication
- Who library permission resolution
- Fine-grained access control
- Token refresh handling

## Implementation Notes

**No external dependencies:**
- Web Crypto API (built-in) for SHA-256
- Fetch API (built-in) for HTTP requests
- No npm, no bundler, no frameworks

**Browser compatibility:**
- Modern browsers only (Chrome, Firefox, Safari, Edge)
- Requires ES6+ support
- Requires Web Crypto API

**File structure:**
```
who-example/
  src/main/resources/
    static/
      tasks.html  # Single-file SPA
```

**Served by Spring Boot:**
- Static resources automatically served from /static
- Available at http://localhost:8080/tasks.html
- No special controller needed
