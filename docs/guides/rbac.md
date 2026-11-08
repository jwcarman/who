# RBAC and Method Security

## Roles and permissions

Use `RbacService` to manage roles and assign them to identities:

```java
@Autowired WhoService whoService;
@Autowired RbacService rbacService;

// Create an identity
Identity identity = whoService.createIdentity();

// Create a role and grant permissions
Role editorRole = rbacService.createRole("editor");
rbacService.addPermissionToRole(editorRole, "task.read");
rbacService.addPermissionToRole(editorRole, "task.write");

// Assign the role to the identity
rbacService.assignRoleToIdentity(identity, editorRole);
```

To look up an existing role by name (throws `RoleNotFoundException` if absent):

```java
Role editorRole = rbacService.findRequiredRole("editor");
```

**Tip:** define your roles as an enum to avoid raw strings throughout your application:

```java
public enum AppRole { EDITOR, VIEWER, ADMIN }

// All three methods accept enum constants directly
Role editorRole = rbacService.createRole(AppRole.EDITOR);
rbacService.assignRoleByName(identity, AppRole.EDITOR);
Role found = rbacService.findRequiredRole(AppRole.EDITOR);
```

Creating a role whose name is taken throws `RoleAlreadyExistsException`, which extends `IllegalArgumentException`. Its message does not name the role.

To ask whether a role exists, without catching an exception, use `findRole`. It returns an `Optional<Role>` and takes a name or an enum constant:

```java
Optional<Role> editor = rbacService.findRole("editor");
Optional<Role> owner = rbacService.findRole(AppRole.ADMIN);
```

`listRoles()` returns every role, sorted by name. `findIdentityIdsWithRole(Role)` returns the ids of the identities that hold a role, sorted by id:

```java
List<Role> roles = rbacService.listRoles();
List<UUID> editors = rbacService.findIdentityIdsWithRole(editorRole);
```

Roles can only be assigned to identities that exist in `who_identity`. Deleting an identity removes its role assignments.

An identity's permissions are the union of the permissions of all its roles.

## Method security

Method security is on by default, so `@RequiresPermission` is enforced with no extra setup. Set `who.method-security.enabled=false` to turn it off. If your application has its own `@EnableMethodSecurity`, Who respects it. Use `@RequiresPermission` from `who-spring-security` to authorize methods:

```java
@GetMapping("/tasks")
@RequiresPermission("task.read")
public List<Task> getTasks(@AuthenticationPrincipal WhoPrincipal principal) {
    return taskService.findAll(principal.identity().id());
}
```

## A typed permission vocabulary

For larger applications, use `@RequiresPermission` as a meta-annotation to build a typed permission vocabulary with no raw strings at the call site:

```java
public interface TaskPermissions {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @RequiresPermission("task.read")
    @interface Read {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @RequiresPermission("task.write")
    @interface Write {}
}

// Usage — no strings at the call site:
@TaskPermissions.Read
public List<Task> getTasks(...) { ... }
```
