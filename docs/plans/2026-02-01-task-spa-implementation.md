# Task Management SPA Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a single-page application that demonstrates Who library permissions with OAuth2/PKCE authentication and full task CRUD operations.

**Architecture:** Pure HTML/CSS/JavaScript SPA using Web Crypto API for PKCE, Fetch API for REST calls, and sessionStorage for token management. No frameworks or build tools.

**Tech Stack:** Vanilla JavaScript (ES6+), Web Crypto API, Fetch API, Spring Boot static resources

---

## Task 1: Create Static Resources Directory and Basic HTML Structure

**Files:**
- Create: `who-example/src/main/resources/static/tasks.html`

**Step 1: Create static resources directory**

```bash
mkdir -p who-example/src/main/resources/static
```

**Step 2: Create basic HTML structure**

Create `who-example/src/main/resources/static/tasks.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Task Manager - Who Framework Demo</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            background: #f5f5f5;
            line-height: 1.6;
        }

        .container {
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
        }

        /* Loading state */
        .loading {
            text-align: center;
            padding: 50px;
            color: #666;
        }

        .error {
            background: #fee;
            border: 1px solid #fcc;
            color: #c33;
            padding: 15px;
            border-radius: 4px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
    <div class="container">
        <div id="app" class="loading">
            <p>Loading...</p>
        </div>
    </div>

    <script>
        // Application will go here
        console.log('Task Manager SPA loaded');
    </script>
</body>
</html>
```

**Step 3: Update SecurityConfig to allow public access to tasks.html**

Modify `who-example/src/main/java/org/jwcarman/who/example/config/SecurityConfig.java`:

Find the line:
```java
.requestMatchers("/", "/authorized").permitAll()
```

Change to:
```java
.requestMatchers("/", "/authorized", "/tasks.html").permitAll()
```

**Step 4: Test basic page loads**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/tasks.html

Expected: Page displays "Loading..." message

**Step 5: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html who-example/src/main/java/org/jwcarman/who/example/config/SecurityConfig.java
git commit -m "feat: add basic SPA structure for task manager"
```

---

## Task 2: Implement PKCE Utilities

**Files:**
- Modify: `who-example/src/main/resources/static/tasks.html`

**Step 1: Add PKCE utility functions**

Add to the `<script>` section:

```javascript
// ============================================================================
// PKCE Utilities
// ============================================================================

/**
 * Generate random string for code verifier
 */
function generateRandomString(length) {
    const array = new Uint8Array(length);
    crypto.getRandomValues(array);
    return base64URLEncode(array);
}

/**
 * Base64URL encode (RFC 4648)
 */
function base64URLEncode(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary)
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=/g, '');
}

/**
 * Generate SHA-256 hash of code verifier
 */
async function generateCodeChallenge(codeVerifier) {
    const encoder = new TextEncoder();
    const data = encoder.encode(codeVerifier);
    const hash = await crypto.subtle.digest('SHA-256', data);
    return base64URLEncode(hash);
}

/**
 * Generate PKCE code verifier and challenge
 */
async function generatePKCE() {
    const codeVerifier = generateRandomString(32);
    const codeChallenge = await generateCodeChallenge(codeVerifier);
    return { codeVerifier, codeChallenge };
}
```

**Step 2: Test PKCE generation in browser console**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/tasks.html

Open browser console and run:
```javascript
generatePKCE().then(pkce => console.log(pkce));
```

Expected: Object with `codeVerifier` and `codeChallenge` properties (both base64url strings)

**Step 3: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html
git commit -m "feat: add PKCE utility functions"
```

---

## Task 3: Implement OAuth2 Authentication Flow

**Files:**
- Modify: `who-example/src/main/resources/static/tasks.html`

**Step 1: Add OAuth2 configuration and token management**

Add to `<script>` section:

```javascript
// ============================================================================
// OAuth2 Configuration
// ============================================================================

const OAUTH_CONFIG = {
    clientId: 'demo-client',
    redirectUri: window.location.origin + '/tasks.html',
    authorizationEndpoint: '/oauth2/authorize',
    tokenEndpoint: '/oauth2/token',
    scopes: 'read write'
};

// ============================================================================
// Token Management
// ============================================================================

/**
 * Get access token from sessionStorage
 */
function getAccessToken() {
    return sessionStorage.getItem('access_token');
}

/**
 * Get refresh token from sessionStorage
 */
function getRefreshToken() {
    return sessionStorage.getItem('refresh_token');
}

/**
 * Get token expiration timestamp
 */
function getTokenExpiry() {
    const expiry = sessionStorage.getItem('token_expiry');
    return expiry ? parseInt(expiry, 10) : 0;
}

/**
 * Check if access token is expired or about to expire (within 30 seconds)
 */
function isTokenExpired() {
    const expiry = getTokenExpiry();
    const now = Math.floor(Date.now() / 1000);
    return expiry - now < 30;
}

/**
 * Store tokens in sessionStorage
 */
function storeTokens(tokenResponse) {
    sessionStorage.setItem('access_token', tokenResponse.access_token);
    if (tokenResponse.refresh_token) {
        sessionStorage.setItem('refresh_token', tokenResponse.refresh_token);
    }
    // Calculate expiry timestamp
    const expiresIn = tokenResponse.expires_in || 300;
    const expiry = Math.floor(Date.now() / 1000) + expiresIn;
    sessionStorage.setItem('token_expiry', expiry.toString());
}

/**
 * Clear all tokens from sessionStorage
 */
function clearTokens() {
    sessionStorage.removeItem('access_token');
    sessionStorage.removeItem('refresh_token');
    sessionStorage.removeItem('token_expiry');
    sessionStorage.removeItem('code_verifier');
}

/**
 * Parse JWT to extract claims (without verification - server validates)
 */
function parseJWT(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(atob(base64).split('').map(c => {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
        return JSON.parse(jsonPayload);
    } catch (e) {
        console.error('Failed to parse JWT:', e);
        return null;
    }
}

/**
 * Get username from access token
 */
function getUsername() {
    const token = getAccessToken();
    if (!token) return null;
    const claims = parseJWT(token);
    return claims ? claims.sub : null;
}
```

**Step 2: Add OAuth2 authorization flow functions**

```javascript
// ============================================================================
// OAuth2 Authorization Flow
// ============================================================================

/**
 * Start OAuth2 authorization code flow with PKCE
 */
async function startLogin() {
    const pkce = await generatePKCE();

    // Store code verifier for later token exchange
    sessionStorage.setItem('code_verifier', pkce.codeVerifier);

    // Build authorization URL
    const params = new URLSearchParams({
        response_type: 'code',
        client_id: OAUTH_CONFIG.clientId,
        redirect_uri: OAUTH_CONFIG.redirectUri,
        scope: OAUTH_CONFIG.scopes,
        code_challenge: pkce.codeChallenge,
        code_challenge_method: 'S256'
    });

    const authUrl = `${OAUTH_CONFIG.authorizationEndpoint}?${params.toString()}`;
    window.location.href = authUrl;
}

/**
 * Handle OAuth2 callback and exchange code for token
 */
async function handleCallback() {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const error = params.get('error');

    if (error) {
        throw new Error(`OAuth2 error: ${error} - ${params.get('error_description')}`);
    }

    if (!code) {
        return false; // No callback in progress
    }

    const codeVerifier = sessionStorage.getItem('code_verifier');
    if (!codeVerifier) {
        throw new Error('No code verifier found - PKCE flow broken');
    }

    // Exchange code for token
    const tokenResponse = await fetch(OAUTH_CONFIG.tokenEndpoint, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({
            grant_type: 'authorization_code',
            code: code,
            redirect_uri: OAUTH_CONFIG.redirectUri,
            client_id: OAUTH_CONFIG.clientId,
            code_verifier: codeVerifier
        })
    });

    if (!tokenResponse.ok) {
        const error = await tokenResponse.text();
        throw new Error(`Token exchange failed: ${error}`);
    }

    const tokens = await tokenResponse.json();
    storeTokens(tokens);

    // Clean up: remove code from URL
    window.history.replaceState({}, document.title, OAUTH_CONFIG.redirectUri);

    return true;
}

/**
 * Refresh access token using refresh token
 */
async function refreshAccessToken() {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
        throw new Error('No refresh token available');
    }

    const response = await fetch(OAUTH_CONFIG.tokenEndpoint, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: refreshToken,
            client_id: OAUTH_CONFIG.clientId
        })
    });

    if (!response.ok) {
        throw new Error('Token refresh failed');
    }

    const tokens = await response.json();
    storeTokens(tokens);
}

/**
 * Logout - clear tokens and redirect to login
 */
function logout() {
    clearTokens();
    window.location.reload();
}
```

**Step 3: Test OAuth2 flow manually**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/tasks.html

Open browser console and run:
```javascript
startLogin();
```

Expected: Redirect to login page, login with alice/password, redirect back with code parameter

**Step 4: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html
git commit -m "feat: implement OAuth2 authorization code flow with PKCE"
```

---

## Task 4: Implement API Client with Token Management

**Files:**
- Modify: `who-example/src/main/resources/static/tasks.html`

**Step 1: Add API client with automatic token refresh**

Add to `<script>` section:

```javascript
// ============================================================================
// API Client
// ============================================================================

/**
 * Make authenticated API request with automatic token refresh
 */
async function apiRequest(url, options = {}) {
    // Ensure we have a valid token
    if (isTokenExpired()) {
        try {
            await refreshAccessToken();
        } catch (e) {
            console.error('Token refresh failed:', e);
            logout();
            throw e;
        }
    }

    const token = getAccessToken();
    if (!token) {
        throw new Error('Not authenticated');
    }

    // Add Authorization header
    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`
    };

    const response = await fetch(url, {
        ...options,
        headers
    });

    // Handle 401 Unauthorized - token might be invalid
    if (response.status === 401) {
        try {
            await refreshAccessToken();
            // Retry request with new token
            headers.Authorization = `Bearer ${getAccessToken()}`;
            return await fetch(url, { ...options, headers });
        } catch (e) {
            logout();
            throw e;
        }
    }

    return response;
}

/**
 * Fetch all tasks for current user
 */
async function fetchTasks() {
    const response = await apiRequest('/api/tasks');
    if (!response.ok) {
        throw new Error(`Failed to fetch tasks: ${response.status}`);
    }
    return await response.json();
}

/**
 * Create a new task
 */
async function createTask(task) {
    const response = await apiRequest('/api/tasks', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(task)
    });
    if (!response.ok) {
        throw new Error(`Failed to create task: ${response.status}`);
    }
    return await response.json();
}

/**
 * Update an existing task
 */
async function updateTask(id, task) {
    const response = await apiRequest(`/api/tasks/${id}`, {
        method: 'PUT',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(task)
    });
    if (!response.ok) {
        throw new Error(`Failed to update task: ${response.status}`);
    }
    return await response.json();
}

/**
 * Delete a task
 */
async function deleteTask(id) {
    const response = await apiRequest(`/api/tasks/${id}`, {
        method: 'DELETE'
    });
    if (!response.ok) {
        throw new Error(`Failed to delete task: ${response.status}`);
    }
}
```

**Step 2: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html
git commit -m "feat: add API client with automatic token refresh"
```

---

## Task 5: Build UI Components and Rendering

**Files:**
- Modify: `who-example/src/main/resources/static/tasks.html`

**Step 1: Add comprehensive CSS styling**

Replace the `<style>` section with:

```css
<style>
    * {
        margin: 0;
        padding: 0;
        box-sizing: border-box;
    }

    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
        background: #f5f5f5;
        line-height: 1.6;
    }

    .container {
        max-width: 800px;
        margin: 0 auto;
        padding: 20px;
    }

    /* Header */
    header {
        background: white;
        padding: 20px;
        border-radius: 8px;
        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        margin-bottom: 20px;
        display: flex;
        justify-content: space-between;
        align-items: center;
    }

    header h1 {
        color: #333;
        font-size: 24px;
    }

    .user-info {
        display: flex;
        align-items: center;
        gap: 15px;
    }

    .username {
        color: #666;
        font-weight: 500;
    }

    /* Buttons */
    button {
        padding: 8px 16px;
        border: none;
        border-radius: 4px;
        cursor: pointer;
        font-size: 14px;
        font-weight: 500;
        transition: background 0.2s;
    }

    .btn-primary {
        background: #007bff;
        color: white;
    }

    .btn-primary:hover {
        background: #0056b3;
    }

    .btn-secondary {
        background: #6c757d;
        color: white;
    }

    .btn-secondary:hover {
        background: #5a6268;
    }

    .btn-danger {
        background: #dc3545;
        color: white;
    }

    .btn-danger:hover {
        background: #c82333;
    }

    .btn-small {
        padding: 4px 8px;
        font-size: 12px;
    }

    /* Add Task Form */
    .add-task-form {
        background: white;
        padding: 20px;
        border-radius: 8px;
        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        margin-bottom: 20px;
    }

    .form-group {
        margin-bottom: 15px;
    }

    .form-group label {
        display: block;
        margin-bottom: 5px;
        color: #333;
        font-weight: 500;
    }

    .form-group input,
    .form-group textarea,
    .form-group select {
        width: 100%;
        padding: 8px 12px;
        border: 1px solid #ddd;
        border-radius: 4px;
        font-size: 14px;
        font-family: inherit;
    }

    .form-group textarea {
        resize: vertical;
        min-height: 80px;
    }

    .form-actions {
        display: flex;
        gap: 10px;
        justify-content: flex-end;
    }

    /* Task List */
    .task-list {
        background: white;
        border-radius: 8px;
        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
    }

    .task-item {
        padding: 20px;
        border-bottom: 1px solid #eee;
    }

    .task-item:last-child {
        border-bottom: none;
    }

    .task-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 10px;
    }

    .task-title {
        font-size: 18px;
        font-weight: 600;
        color: #333;
        margin-bottom: 5px;
    }

    .task-status {
        display: inline-block;
        padding: 4px 8px;
        border-radius: 4px;
        font-size: 12px;
        font-weight: 500;
    }

    .task-status.TODO {
        background: #fff3cd;
        color: #856404;
    }

    .task-status.IN_PROGRESS {
        background: #cfe2ff;
        color: #084298;
    }

    .task-status.DONE {
        background: #d1e7dd;
        color: #0f5132;
    }

    .task-description {
        color: #666;
        margin-bottom: 15px;
        white-space: pre-wrap;
    }

    .task-actions {
        display: flex;
        gap: 10px;
    }

    /* Empty State */
    .empty-state {
        padding: 60px 20px;
        text-align: center;
        color: #999;
    }

    .empty-state p {
        font-size: 18px;
        margin-bottom: 10px;
    }

    /* Loading and Error States */
    .loading {
        text-align: center;
        padding: 50px;
        color: #666;
    }

    .error {
        background: #fee;
        border: 1px solid #fcc;
        color: #c33;
        padding: 15px;
        border-radius: 4px;
        margin: 20px 0;
    }

    /* Edit Form (inline) */
    .task-edit-form {
        margin-top: 15px;
        padding-top: 15px;
        border-top: 1px solid #eee;
    }

    .hidden {
        display: none;
    }
</style>
```

**Step 2: Add UI rendering functions**

Add to `<script>` section:

```javascript
// ============================================================================
// UI State
// ============================================================================

let tasks = [];
let editingTaskId = null;

// ============================================================================
// UI Rendering
// ============================================================================

/**
 * Render the entire application UI
 */
function renderApp() {
    const app = document.getElementById('app');
    const username = getUsername();

    app.innerHTML = `
        <header>
            <h1>Task Manager</h1>
            <div class="user-info">
                <span class="username">${escapeHtml(username)}</span>
                <button class="btn-secondary" onclick="logout()">Logout</button>
            </div>
        </header>

        <div class="add-task-form">
            <h2 style="margin-bottom: 15px;">Add New Task</h2>
            <form id="add-task-form" onsubmit="handleAddTask(event)">
                <div class="form-group">
                    <label for="task-title">Title</label>
                    <input type="text" id="task-title" required maxlength="100">
                </div>
                <div class="form-group">
                    <label for="task-description">Description</label>
                    <textarea id="task-description" maxlength="500"></textarea>
                </div>
                <div class="form-group">
                    <label for="task-status">Status</label>
                    <select id="task-status">
                        <option value="TODO">To Do</option>
                        <option value="IN_PROGRESS">In Progress</option>
                        <option value="DONE">Done</option>
                    </select>
                </div>
                <div class="form-actions">
                    <button type="submit" class="btn-primary">Add Task</button>
                </div>
            </form>
        </div>

        <div class="task-list" id="task-list">
            ${renderTaskList()}
        </div>
    `;
}

/**
 * Render task list
 */
function renderTaskList() {
    if (tasks.length === 0) {
        return `
            <div class="empty-state">
                <p>No tasks yet</p>
                <p style="font-size: 14px;">Create your first task above!</p>
            </div>
        `;
    }

    return tasks.map(task => `
        <div class="task-item">
            <div class="task-header">
                <div>
                    <div class="task-title">${escapeHtml(task.title)}</div>
                    <span class="task-status ${task.status}">${formatStatus(task.status)}</span>
                </div>
            </div>
            <div class="task-description">${escapeHtml(task.description || '')}</div>
            ${editingTaskId === task.id ? renderEditForm(task) : renderTaskActions(task)}
        </div>
    `).join('');
}

/**
 * Render task action buttons
 */
function renderTaskActions(task) {
    return `
        <div class="task-actions">
            <button class="btn-primary btn-small" onclick="startEditTask('${task.id}')">Edit</button>
            <button class="btn-danger btn-small" onclick="handleDeleteTask('${task.id}')">Delete</button>
        </div>
    `;
}

/**
 * Render inline edit form
 */
function renderEditForm(task) {
    return `
        <form class="task-edit-form" onsubmit="handleUpdateTask(event, '${task.id}')">
            <div class="form-group">
                <label>Title</label>
                <input type="text" id="edit-title-${task.id}" value="${escapeHtml(task.title)}" required maxlength="100">
            </div>
            <div class="form-group">
                <label>Description</label>
                <textarea id="edit-description-${task.id}" maxlength="500">${escapeHtml(task.description || '')}</textarea>
            </div>
            <div class="form-group">
                <label>Status</label>
                <select id="edit-status-${task.id}">
                    <option value="TODO" ${task.status === 'TODO' ? 'selected' : ''}>To Do</option>
                    <option value="IN_PROGRESS" ${task.status === 'IN_PROGRESS' ? 'selected' : ''}>In Progress</option>
                    <option value="DONE" ${task.status === 'DONE' ? 'selected' : ''}>Done</option>
                </select>
            </div>
            <div class="form-actions">
                <button type="submit" class="btn-primary btn-small">Save</button>
                <button type="button" class="btn-secondary btn-small" onclick="cancelEdit()">Cancel</button>
            </div>
        </form>
    `;
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Format status for display
 */
function formatStatus(status) {
    return status.replace('_', ' ');
}

/**
 * Re-render task list only (optimization)
 */
function rerenderTaskList() {
    const taskList = document.getElementById('task-list');
    if (taskList) {
        taskList.innerHTML = renderTaskList();
    }
}
```

**Step 3: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html
git commit -m "feat: add UI components and rendering functions"
```

---

## Task 6: Implement Event Handlers and Application Initialization

**Files:**
- Modify: `who-example/src/main/resources/static/tasks.html`

**Step 1: Add event handlers**

Add to `<script>` section:

```javascript
// ============================================================================
// Event Handlers
// ============================================================================

/**
 * Handle add task form submission
 */
async function handleAddTask(event) {
    event.preventDefault();

    const title = document.getElementById('task-title').value;
    const description = document.getElementById('task-description').value;
    const status = document.getElementById('task-status').value;

    try {
        const newTask = await createTask({ title, description, status });
        tasks.push(newTask);
        rerenderTaskList();

        // Clear form
        document.getElementById('add-task-form').reset();
    } catch (error) {
        console.error('Failed to create task:', error);
        alert('Failed to create task: ' + error.message);
    }
}

/**
 * Start editing a task
 */
function startEditTask(taskId) {
    editingTaskId = taskId;
    rerenderTaskList();
}

/**
 * Cancel task editing
 */
function cancelEdit() {
    editingTaskId = null;
    rerenderTaskList();
}

/**
 * Handle update task form submission
 */
async function handleUpdateTask(event, taskId) {
    event.preventDefault();

    const title = document.getElementById(`edit-title-${taskId}`).value;
    const description = document.getElementById(`edit-description-${taskId}`).value;
    const status = document.getElementById(`edit-status-${taskId}`).value;

    try {
        const updatedTask = await updateTask(taskId, { title, description, status });

        // Update in local state
        const index = tasks.findIndex(t => t.id === taskId);
        if (index !== -1) {
            tasks[index] = updatedTask;
        }

        editingTaskId = null;
        rerenderTaskList();
    } catch (error) {
        console.error('Failed to update task:', error);
        alert('Failed to update task: ' + error.message);
    }
}

/**
 * Handle delete task
 */
async function handleDeleteTask(taskId) {
    if (!confirm('Are you sure you want to delete this task?')) {
        return;
    }

    try {
        await deleteTask(taskId);

        // Remove from local state
        tasks = tasks.filter(t => t.id !== taskId);
        rerenderTaskList();
    } catch (error) {
        console.error('Failed to delete task:', error);
        alert('Failed to delete task: ' + error.message);
    }
}
```

**Step 2: Add application initialization**

Add to `<script>` section:

```javascript
// ============================================================================
// Application Initialization
// ============================================================================

/**
 * Load tasks from API
 */
async function loadTasks() {
    try {
        tasks = await fetchTasks();
        renderApp();
    } catch (error) {
        console.error('Failed to load tasks:', error);
        document.getElementById('app').innerHTML = `
            <div class="error">
                <strong>Error:</strong> Failed to load tasks. ${error.message}
            </div>
        `;
    }
}

/**
 * Initialize application
 */
async function init() {
    try {
        // Check if returning from OAuth callback
        const callbackHandled = await handleCallback();

        // Check if we have a valid token
        const token = getAccessToken();

        if (!token) {
            // No token - start login flow
            await startLogin();
            return;
        }

        // We have a token - load the app
        await loadTasks();

    } catch (error) {
        console.error('Initialization error:', error);
        document.getElementById('app').innerHTML = `
            <div class="error">
                <strong>Error:</strong> ${error.message}
                <br><br>
                <button class="btn-primary" onclick="location.reload()">Retry</button>
            </div>
        `;
    }
}

// Start the application when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
```

**Step 3: Test complete application flow**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/tasks.html

Expected flow:
1. Redirect to login page
2. Login with alice/password
3. Redirect back to tasks.html
4. See task list (empty initially)
5. Add a task via form
6. Task appears in list
7. Edit task
8. Delete task

**Step 4: Test with different users**

Logout and login as:
- bob/password (should see only bob's tasks)
- admin/password (should see only admin's tasks)

**Step 5: Commit**

```bash
git add who-example/src/main/resources/static/tasks.html
git commit -m "feat: add event handlers and application initialization"
```

---

## Task 7: Update SecurityConfig to Allow Static Resources

**Files:**
- Modify: `who-example/src/main/java/org/jwcarman/who/example/config/SecurityConfig.java`

**Step 1: Ensure static resources are publicly accessible**

The SecurityConfig already permits `/tasks.html` from Task 1. Verify the configuration:

```java
.requestMatchers("/", "/authorized", "/tasks.html").permitAll()
```

If you want to allow ALL static resources (CSS, JS, images), use:

```java
.requestMatchers("/", "/authorized", "/tasks.html", "/static/**").permitAll()
```

**Step 2: Test application access**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/tasks.html

Expected: No 401/403 errors, smooth login flow

**Step 3: Commit if changes made**

```bash
git add who-example/src/main/java/org/jwcarman/who/example/config/SecurityConfig.java
git commit -m "chore: ensure static resources are publicly accessible"
```

---

## Task 8: Manual End-to-End Testing

**No code changes - verification only**

**Step 1: Start application**

```bash
mvn spring-boot:run -pl who-example
```

**Step 2: Test complete user journey as Alice**

1. Visit http://localhost:8080/tasks.html
2. Should redirect to login
3. Login as alice/password
4. Should redirect back and show empty task list
5. Create task: "Buy groceries", "Milk, eggs, bread", status TODO
6. Verify task appears in list
7. Edit task: change status to IN_PROGRESS
8. Verify task updated
9. Create another task: "Review PR", "Check code quality", status TODO
10. Delete first task
11. Verify only second task remains
12. Click logout

Expected: All operations work, no console errors

**Step 3: Test as Bob (data isolation)**

1. Visit http://localhost:8080/tasks.html
2. Login as bob/password
3. Should see empty task list (Alice's tasks not visible)
4. Create task: "Write documentation"
5. Verify task appears
6. Logout

Expected: Bob sees only his own tasks

**Step 4: Test token refresh (optional)**

1. Login as alice/password
2. Wait for token to expire (5 minutes by default)
3. Create a task
4. Check browser console - should see token refresh happening
5. Task should be created successfully

Expected: Automatic token refresh works

**Step 5: Test error handling**

1. Login as alice/password
2. Open browser DevTools → Network tab
3. Try to create a task while offline (disable network)
4. Should see error message
5. Re-enable network and retry

Expected: Graceful error handling

---

## Task 9: Update Index Page to Link to SPA

**Files:**
- Modify: `who-example/src/main/resources/templates/index.html`

**Step 1: Add link to task manager SPA**

Find the section with the "Start OAuth2 Flow" button and add a new section after it:

```html
<h2>🎯 Interactive Task Manager</h2>
<p>Try the full-featured task management application with OAuth2 authentication:</p>

<a href="/tasks.html" class="btn">Open Task Manager →</a>

<div class="info-box">
    <strong>ℹ️ Features:</strong>
    <ul>
        <li>Complete OAuth2 Authorization Code Flow with PKCE</li>
        <li>Client-side SPA with automatic token refresh</li>
        <li>Create, read, update, and delete tasks</li>
        <li>Demonstrates Who library permission-based access control</li>
        <li>Each user sees only their own tasks</li>
    </ul>
</div>
```

**Step 2: Test updated index page**

Run: `mvn spring-boot:run -pl who-example`

Visit: http://localhost:8080/

Expected: See new "Interactive Task Manager" section with link

**Step 3: Commit**

```bash
git add who-example/src/main/resources/templates/index.html
git commit -m "docs: add link to task manager SPA from index page"
```

---

## Task 10: Final Testing and Documentation

**Files:**
- Create: `who-example/README.md` (if doesn't exist)
- Modify: `who-example/README.md`

**Step 1: Add documentation for the SPA**

Create or update `who-example/README.md`:

```markdown
# Who Framework - Example Application

This example application demonstrates the Who library's permission-based access control integrated with Spring Security and OAuth2.

## Features

### OAuth2 Authorization Server
- Built-in authorization server with authorization code flow
- Client credentials grant for machine-to-machine auth
- JWT token generation with Who permissions

### Interactive Task Manager SPA
- Single-page application with pure JavaScript (no frameworks)
- OAuth2 Authorization Code Flow with PKCE
- Automatic token refresh
- Full CRUD operations on tasks
- Client-side token management (sessionStorage)

### REST API
- `/api/tasks` - Task management endpoints
- JWT bearer token authentication
- Permission-based access control via Who library

## Getting Started

1. **Start the application:**
   ```bash
   mvn spring-boot:run -pl who-example
   ```

2. **Access the home page:**
   Visit http://localhost:8080/

3. **Try the interactive task manager:**
   Visit http://localhost:8080/tasks.html

## Test Users

| Username | Password | Permissions |
|----------|----------|-------------|
| alice    | password | task.own.read, task.own.write |
| bob      | password | task.own.read, task.own.write |
| admin    | password | task.own.read, task.own.write, task.all.read, task.all.write, user.manage |

## Architecture

### Backend (Spring Boot)
- OAuth2 Authorization Server (Spring Authorization Server)
- OAuth2 Resource Server (JWT validation)
- REST API with method-level security
- Who library integration for permission resolution

### Frontend (tasks.html)
- Pure HTML/CSS/JavaScript SPA
- Web Crypto API for PKCE implementation
- Fetch API for REST calls
- sessionStorage for token management
- No external dependencies

### Security Flow
1. User visits `/tasks.html`
2. Redirects to `/oauth2/authorize` with PKCE challenge
3. User authenticates (form login)
4. Authorization server redirects back with code
5. SPA exchanges code for access/refresh tokens (with PKCE verifier)
6. SPA calls `/api/tasks` with bearer token
7. Spring Security validates JWT, resolves Who permissions
8. API returns user's tasks based on permissions

## API Examples

### Using curl with client credentials:
```bash
# Get access token
curl -X POST http://localhost:8080/oauth2/token \
  -u demo-client:secret \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&scope=read write"

# Use token to list tasks
curl -H "Authorization: Bearer <access_token>" \
  http://localhost:8080/api/tasks
```

### Using the SPA:
Just visit http://localhost:8080/tasks.html and login!

## Technology Stack

- Java 21
- Spring Boot 3.x
- Spring Security 6.x
- Spring Authorization Server
- Who Library (JDBC implementation)
- H2 Database (in-memory)
- Thymeleaf (for static pages)
- Vanilla JavaScript (SPA)
```

**Step 2: Run final comprehensive test**

```bash
mvn clean install
mvn spring-boot:run -pl who-example
```

Test all functionality:
- Home page loads
- OAuth2 authorization code flow (curl)
- Client credentials flow (curl)
- Task manager SPA (browser)
- Multiple users (alice, bob, admin)
- CRUD operations
- Token refresh
- Logout

**Step 3: Commit**

```bash
git add who-example/README.md
git commit -m "docs: add comprehensive README for example application"
```

---

## Completion

All tasks complete! The task management SPA demonstrates:

✅ OAuth2 Authorization Code Flow with PKCE
✅ Client-side token management
✅ Automatic token refresh
✅ Who library permission-based access control
✅ Full CRUD operations
✅ Clean, modern UI
✅ No external dependencies
✅ Production-ready security practices (for SPAs)

The application is ready for users to explore the Who library capabilities through an interactive, user-friendly interface.
