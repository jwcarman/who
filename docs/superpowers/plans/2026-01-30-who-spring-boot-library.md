# Who Spring Boot Library Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a reusable Spring Boot library (`org.jwcarman.who`) providing OAuth2/JWT authentication, internal identity mapping, RBAC authorization, and JSON-based user preferences.

**Architecture:** Multi-module Maven project with core domain, JPA persistence, Spring Security integration, optional REST controllers, and a starter for auto-configuration. JWT tokens map to internal stable user IDs, roles grant permissions (string-based authorities), and preferences are stored as namespaced JSON documents with merge support.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Security 6.x OAuth2 Resource Server, JPA/Hibernate, Jackson, Liquibase, Maven multi-module

---

## Task 1: Project Structure and Parent POM

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `README.md`

**Step 1: Create parent POM**

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>Who - Parent</name>
    <description>Spring Boot identity, entitlements, and personalization framework</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>3.2.2</spring-boot.version>
    </properties>

    <modules>
        <module>who-core</module>
        <module>who-jpa</module>
        <module>who-security</module>
        <module>who-web</module>
        <module>who-starter</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.12.1</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

**Step 2: Create .gitignore**

Create `.gitignore`:

```
target/
.idea/
*.iml
.DS_Store
*.log
.classpath
.project
.settings/
```

**Step 3: Create README**

Create `README.md`:

```markdown
# Who - Spring Boot Identity & Entitlements Framework

A reusable Spring Boot library for OAuth2/JWT authentication, internal identity mapping, RBAC authorization, and user preferences.

## Modules

- `who-core` - Core domain types and interfaces
- `who-jpa` - JPA entities and repositories
- `who-security` - Spring Security integration
- `who-web` - REST controllers (optional)
- `who-starter` - Spring Boot auto-configuration

## Build

```bash
mvn clean install
```

## License

TBD
```

**Step 4: Commit project structure**

```bash
git add pom.xml .gitignore README.md
git commit -m "chore: initialize project structure with parent POM"
```

---

## Task 2: who-core Module - Domain Types

**Files:**
- Create: `who-core/pom.xml`
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/WhoPrincipal.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/ExternalIdentityKey.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/UserStatus.java`
- Create: `who-core/src/test/java/org/jwcarman/who/core/domain/WhoPrincipalTest.java`

**Step 1: Create who-core POM**

Create `who-core/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.jwcarman.who</groupId>
        <artifactId>who-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>who-core</artifactId>
    <name>Who - Core</name>
    <description>Core domain types and interfaces</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 2: Write failing test for WhoPrincipal**

Create `who-core/src/test/java/org/jwcarman/who/core/domain/WhoPrincipalTest.java`:

```java
package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WhoPrincipalTest {

    @Test
    void shouldCreatePrincipalWithRequiredFields() {
        UUID userId = UUID.randomUUID();
        String issuer = "https://auth.example.com";
        String subject = "user123";
        Set<String> permissions = Set.of("billing.invoice.read", "billing.invoice.write");

        WhoPrincipal principal = new WhoPrincipal(userId, issuer, subject, permissions);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.issuer()).isEqualTo(issuer);
        assertThat(principal.subject()).isEqualTo(subject);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnImmutablePermissionsSet() {
        UUID userId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(
            userId, "iss", "sub", Set.of("perm1"));

        assertThat(principal.permissions()).isUnmodifiable();
    }
}
```

**Step 3: Run test to verify it fails**

```bash
cd who-core
mvn test -Dtest=WhoPrincipalTest
```

Expected: FAIL with compilation error "cannot find symbol: class WhoPrincipal"

**Step 4: Implement WhoPrincipal**

Create `who-core/src/main/java/org/jwcarman/who/core/domain/WhoPrincipal.java`:

```java
package org.jwcarman.who.core.domain;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Principal representing an authenticated user with resolved permissions.
 * Used by Spring Security authentication.
 */
public record WhoPrincipal(
    UUID userId,
    String issuer,
    String subject,
    Set<String> permissions
) {
    public WhoPrincipal {
        permissions = Collections.unmodifiableSet(Set.copyOf(permissions));
    }
}
```

**Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=WhoPrincipalTest
```

Expected: PASS (all tests green)

**Step 6: Write test for ExternalIdentityKey**

Create `who-core/src/test/java/org/jwcarman/who/core/domain/ExternalIdentityKeyTest.java`:

```java
package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalIdentityKeyTest {

    @Test
    void shouldCreateKeyWithIssuerAndSubject() {
        String issuer = "https://auth.example.com";
        String subject = "user123";

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);

        assertThat(key.issuer()).isEqualTo(issuer);
        assertThat(key.subject()).isEqualTo(subject);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        ExternalIdentityKey key1 = new ExternalIdentityKey("iss", "sub");
        ExternalIdentityKey key2 = new ExternalIdentityKey("iss", "sub");
        ExternalIdentityKey key3 = new ExternalIdentityKey("iss", "different");

        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        assertThat(key1).isNotEqualTo(key3);
    }
}
```

**Step 7: Run test to verify it fails**

```bash
mvn test -Dtest=ExternalIdentityKeyTest
```

Expected: FAIL with compilation error

**Step 8: Implement ExternalIdentityKey**

Create `who-core/src/main/java/org/jwcarman/who/core/domain/ExternalIdentityKey.java`:

```java
package org.jwcarman.who.core.domain;

/**
 * Composite key identifying an external identity by issuer and subject.
 */
public record ExternalIdentityKey(String issuer, String subject) {
}
```

**Step 9: Run test to verify it passes**

```bash
mvn test -Dtest=ExternalIdentityKeyTest
```

Expected: PASS

**Step 10: Create UserStatus enum**

Create `who-core/src/main/java/org/jwcarman/who/core/domain/UserStatus.java`:

```java
package org.jwcarman.who.core.domain;

/**
 * Status of a user account.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED
}
```

**Step 11: Run all tests**

```bash
mvn test
```

Expected: PASS (all tests green)

**Step 12: Commit core domain types**

```bash
cd ..
git add who-core/
git commit -m "feat(core): add WhoPrincipal, ExternalIdentityKey, and UserStatus domain types"
```

---

## Task 3: who-core - Service Interfaces

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/EntitlementsService.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/UserProvisioningPolicy.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/WhoManagementService.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/PreferencesService.java`

**Step 1: Create EntitlementsService interface**

Create `who-core/src/main/java/org/jwcarman/who/core/service/EntitlementsService.java`:

```java
package org.jwcarman.who.core.service;

import java.util.Set;
import java.util.UUID;

/**
 * Service for resolving user entitlements (permissions).
 */
public interface EntitlementsService {

    /**
     * Resolve effective permissions for a user.
     *
     * @param userId the internal user ID
     * @return set of permission strings (e.g., "billing.invoice.read")
     */
    Set<String> resolvePermissions(UUID userId);
}
```

**Step 2: Create UserProvisioningPolicy interface**

Create `who-core/src/main/java/org/jwcarman/who/core/service/UserProvisioningPolicy.java`:

```java
package org.jwcarman.who.core.service;

import org.jwcarman.who.core.domain.ExternalIdentityKey;

import java.util.UUID;

/**
 * Policy for handling unknown external identities.
 */
public interface UserProvisioningPolicy {

    /**
     * Handle an unknown external identity.
     *
     * @param identityKey the external identity key
     * @return internal user ID (new or existing), or null to deny access
     */
    UUID handleUnknownIdentity(ExternalIdentityKey identityKey);
}
```

**Step 3: Create WhoManagementService interface**

Create `who-core/src/main/java/org/jwcarman/who/core/service/WhoManagementService.java`:

```java
package org.jwcarman.who.core.service;

import java.util.UUID;

/**
 * Service for managing users, roles, and identities.
 */
public interface WhoManagementService {

    // External identity management
    void linkExternalIdentity(UUID userId, String issuer, String subject);
    void unlinkExternalIdentity(UUID userId, UUID externalIdentityId);

    // Role management
    UUID createRole(String roleName);
    void deleteRole(UUID roleId);
    void assignRoleToUser(UUID userId, UUID roleId);
    void removeRoleFromUser(UUID userId, UUID roleId);

    // Permission management for roles
    void addPermissionToRole(UUID roleId, String permission);
    void removePermissionFromRole(UUID roleId, String permission);
}
```

**Step 4: Create PreferencesService interface**

Create `who-core/src/main/java/org/jwcarman/who/core/service/PreferencesService.java`:

```java
package org.jwcarman.who.core.service;

import java.util.UUID;

/**
 * Service for managing user preferences.
 */
public interface PreferencesService {

    /**
     * Get user preferences for a namespace.
     *
     * @param userId the user ID
     * @param namespace the preferences namespace
     * @param type the preference class type
     * @return deserialized preferences with defaults applied
     */
    <T> T getPreferences(UUID userId, String namespace, Class<T> type);

    /**
     * Set user preferences for a namespace (replace).
     *
     * @param userId the user ID
     * @param namespace the preferences namespace
     * @param preferences the preferences object
     */
    <T> void setPreferences(UUID userId, String namespace, T preferences);

    /**
     * Merge preference layers (later layers override earlier).
     *
     * @param type the preference class type
     * @param layers preference layers to merge
     * @return merged preferences
     */
    @SuppressWarnings("unchecked")
    <T> T mergePreferences(Class<T> type, T... layers);
}
```

**Step 5: Commit service interfaces**

```bash
git add who-core/src/main/java/org/jwcarman/who/core/service/
git commit -m "feat(core): add service interfaces for entitlements, provisioning, management, and preferences"
```

---

## Task 4: who-jpa Module - JPA Entities

**Files:**
- Create: `who-jpa/pom.xml`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserEntity.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/ExternalIdentityEntity.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/RoleEntity.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/RolePermissionEntity.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserRoleEntity.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserPreferencesEntity.java`

**Step 1: Create who-jpa POM**

Create `who-jpa/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.jwcarman.who</groupId>
        <artifactId>who-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>who-jpa</artifactId>
    <name>Who - JPA</name>
    <description>JPA entities and repositories</description>

    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.liquibase</groupId>
            <artifactId>liquibase-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 2: Create UserEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;
import org.jwcarman.who.core.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "who_user")
public class UserEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = UserStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

**Step 3: Create ExternalIdentityEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/ExternalIdentityEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "who_external_identity",
       uniqueConstraints = @UniqueConstraint(columnNames = {"issuer", "subject"}))
public class ExternalIdentityEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "provider_hint")
    private String providerHint;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (firstSeenAt == null) {
            firstSeenAt = now;
        }
        lastSeenAt = now;
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getProviderHint() {
        return providerHint;
    }

    public void setProviderHint(String providerHint) {
        this.providerHint = providerHint;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
```

**Step 4: Create RoleEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/RoleEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "who_role",
       uniqueConstraints = @UniqueConstraint(columnNames = {"name"}))
public class RoleEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
```

**Step 5: Create RolePermissionEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/RolePermissionEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "who_role_permission")
@IdClass(RolePermissionEntity.RolePermissionId.class)
public class RolePermissionEntity {

    @Id
    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Id
    @Column(name = "permission", nullable = false)
    private String permission;

    // Getters and setters
    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    // Composite key class
    public static class RolePermissionId implements Serializable {
        private UUID roleId;
        private String permission;

        public RolePermissionId() {}

        public RolePermissionId(UUID roleId, String permission) {
            this.roleId = roleId;
            this.permission = permission;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RolePermissionId that = (RolePermissionId) o;
            return Objects.equals(roleId, that.roleId) &&
                   Objects.equals(permission, that.permission);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permission);
        }
    }
}
```

**Step 6: Create UserRoleEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserRoleEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "who_user_role")
@IdClass(UserRoleEntity.UserRoleId.class)
public class UserRoleEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    // Getters and setters
    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    // Composite key class
    public static class UserRoleId implements Serializable {
        private UUID userId;
        private UUID roleId;

        public UserRoleId() {}

        public UserRoleId(UUID userId, UUID roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UserRoleId that = (UserRoleId) o;
            return Objects.equals(userId, that.userId) &&
                   Objects.equals(roleId, that.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
```

**Step 7: Create UserPreferencesEntity**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/entity/UserPreferencesEntity.java`:

```java
package org.jwcarman.who.jpa.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "who_user_preferences")
@IdClass(UserPreferencesEntity.UserPreferencesId.class)
public class UserPreferencesEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "namespace", nullable = false)
    private String namespace;

    @Column(name = "prefs_json", nullable = false, columnDefinition = "TEXT")
    private String prefsJson;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getPrefsJson() {
        return prefsJson;
    }

    public void setPrefsJson(String prefsJson) {
        this.prefsJson = prefsJson;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Composite key class
    public static class UserPreferencesId implements Serializable {
        private UUID userId;
        private String namespace;

        public UserPreferencesId() {}

        public UserPreferencesId(UUID userId, String namespace) {
            this.userId = userId;
            this.namespace = namespace;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UserPreferencesId that = (UserPreferencesId) o;
            return Objects.equals(userId, that.userId) &&
                   Objects.equals(namespace, that.namespace);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, namespace);
        }
    }
}
```

**Step 8: Commit JPA entities**

```bash
git add who-jpa/
git commit -m "feat(jpa): add JPA entities for users, identities, roles, and preferences"
```

---

## Task 5: who-jpa - Liquibase Database Schema

**Files:**
- Create: `who-jpa/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `who-jpa/src/main/resources/db/changelog/changes/001-initial-schema.yaml`

**Step 1: Create Liquibase master changelog**

Create `who-jpa/src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/changes/001-initial-schema.yaml
```

**Step 2: Create initial schema changelog**

Create `who-jpa/src/main/resources/db/changelog/changes/001-initial-schema.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-who-user-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_user
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: status
                  type: varchar(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamp
                  constraints:
                    nullable: false

  - changeSet:
      id: 001-create-who-external-identity-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_external_identity
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: user_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: issuer
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: subject
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: provider_hint
                  type: varchar(100)
              - column:
                  name: first_seen_at
                  type: timestamp
              - column:
                  name: last_seen_at
                  type: timestamp
        - addForeignKeyConstraint:
            baseTableName: who_external_identity
            baseColumnNames: user_id
            constraintName: fk_external_identity_user
            referencedTableName: who_user
            referencedColumnNames: id
        - addUniqueConstraint:
            tableName: who_external_identity
            columnNames: issuer, subject
            constraintName: uk_external_identity_issuer_subject

  - changeSet:
      id: 001-create-who-role-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_role
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: name
                  type: varchar(100)
                  constraints:
                    nullable: false
                    unique: true

  - changeSet:
      id: 001-create-who-role-permission-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_role_permission
            columns:
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: permission
                  type: varchar(255)
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: who_role_permission
            columnNames: role_id, permission
            constraintName: pk_role_permission
        - addForeignKeyConstraint:
            baseTableName: who_role_permission
            baseColumnNames: role_id
            constraintName: fk_role_permission_role
            referencedTableName: who_role
            referencedColumnNames: id

  - changeSet:
      id: 001-create-who-user-role-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_user_role
            columns:
              - column:
                  name: user_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: who_user_role
            columnNames: user_id, role_id
            constraintName: pk_user_role
        - addForeignKeyConstraint:
            baseTableName: who_user_role
            baseColumnNames: user_id
            constraintName: fk_user_role_user
            referencedTableName: who_user
            referencedColumnNames: id
        - addForeignKeyConstraint:
            baseTableName: who_user_role
            baseColumnNames: role_id
            constraintName: fk_user_role_role
            referencedTableName: who_role
            referencedColumnNames: id

  - changeSet:
      id: 001-create-who-user-preferences-table
      author: jwcarman
      changes:
        - createTable:
            tableName: who_user_preferences
            columns:
              - column:
                  name: user_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: namespace
                  type: varchar(100)
                  constraints:
                    nullable: false
              - column:
                  name: prefs_json
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: version
                  type: bigint
              - column:
                  name: updated_at
                  type: timestamp
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: who_user_preferences
            columnNames: user_id, namespace
            constraintName: pk_user_preferences
        - addForeignKeyConstraint:
            baseTableName: who_user_preferences
            baseColumnNames: user_id
            constraintName: fk_user_preferences_user
            referencedTableName: who_user
            referencedColumnNames: id
```

**Step 3: Commit Liquibase schema**

```bash
git add who-jpa/src/main/resources/
git commit -m "feat(jpa): add Liquibase database schema migrations"
```

---

## Task 6: who-jpa - JPA Repositories

**Files:**
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserRepository.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/ExternalIdentityRepository.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/RoleRepository.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/RolePermissionRepository.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserRoleRepository.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserPreferencesRepository.java`

**Step 1: Create UserRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
}
```

**Step 2: Create ExternalIdentityRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/ExternalIdentityRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentityEntity, UUID> {

    Optional<ExternalIdentityEntity> findByIssuerAndSubject(String issuer, String subject);

    List<ExternalIdentityEntity> findByUserId(UUID userId);

    long countByUserId(UUID userId);
}
```

**Step 3: Create RoleRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/RoleRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByName(String name);
}
```

**Step 4: Create RolePermissionRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/RolePermissionRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository extends
        JpaRepository<RolePermissionEntity, RolePermissionEntity.RolePermissionId> {

    List<RolePermissionEntity> findByRoleId(UUID roleId);

    @Query("SELECT rp.permission FROM RolePermissionEntity rp WHERE rp.roleId IN :roleIds")
    List<String> findPermissionsByRoleIds(List<UUID> roleIds);

    void deleteByRoleId(UUID roleId);
}
```

**Step 5: Create UserRoleRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserRoleRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.UserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends
        JpaRepository<UserRoleEntity, UserRoleEntity.UserRoleId> {

    @Query("SELECT ur.roleId FROM UserRoleEntity ur WHERE ur.userId = :userId")
    List<UUID> findRoleIdsByUserId(UUID userId);

    void deleteByUserIdAndRoleId(UUID userId, UUID roleId);
}
```

**Step 6: Create UserPreferencesRepository**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/repository/UserPreferencesRepository.java`:

```java
package org.jwcarman.who.jpa.repository;

import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserPreferencesRepository extends
        JpaRepository<UserPreferencesEntity, UserPreferencesEntity.UserPreferencesId> {

    Optional<UserPreferencesEntity> findByUserIdAndNamespace(UUID userId, String namespace);
}
```

**Step 7: Commit JPA repositories**

```bash
git add who-jpa/src/main/java/org/jwcarman/who/jpa/repository/
git commit -m "feat(jpa): add Spring Data JPA repositories"
```

---

## Task 7: who-jpa - Service Implementations

**Files:**
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaEntitlementsService.java`
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaWhoManagementService.java`
- Create: `who-jpa/src/test/java/org/jwcarman/who/jpa/service/JpaEntitlementsServiceTest.java`

**Step 1: Write failing test for JpaEntitlementsService**

Create `who-jpa/src/test/java/org/jwcarman/who/jpa/service/JpaEntitlementsServiceTest.java`:

```java
package org.jwcarman.who.jpa.service;

import org.jwcarman.who.jpa.repository.RolePermissionRepository;
import org.jwcarman.who.jpa.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaEntitlementsServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @InjectMocks
    private JpaEntitlementsService service;

    @Test
    void shouldResolvePermissionsFromUserRoles() {
        UUID userId = UUID.randomUUID();
        UUID roleId1 = UUID.randomUUID();
        UUID roleId2 = UUID.randomUUID();

        when(userRoleRepository.findRoleIdsByUserId(userId))
            .thenReturn(List.of(roleId1, roleId2));
        when(rolePermissionRepository.findPermissionsByRoleIds(List.of(roleId1, roleId2)))
            .thenReturn(List.of("billing.invoice.read", "billing.invoice.write", "billing.invoice.read"));

        Set<String> permissions = service.resolvePermissions(userId);

        assertThat(permissions).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnEmptySetWhenUserHasNoRoles() {
        UUID userId = UUID.randomUUID();

        when(userRoleRepository.findRoleIdsByUserId(userId))
            .thenReturn(List.of());

        Set<String> permissions = service.resolvePermissions(userId);

        assertThat(permissions).isEmpty();
    }
}
```

**Step 2: Run test to verify it fails**

```bash
cd who-jpa
mvn test -Dtest=JpaEntitlementsServiceTest
```

Expected: FAIL with compilation error

**Step 3: Implement JpaEntitlementsService**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaEntitlementsService.java`:

```java
package org.jwcarman.who.jpa.service;

import org.jwcarman.who.core.service.EntitlementsService;
import org.jwcarman.who.jpa.repository.RolePermissionRepository;
import org.jwcarman.who.jpa.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JpaEntitlementsService implements EntitlementsService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public JpaEntitlementsService(
            UserRoleRepository userRoleRepository,
            RolePermissionRepository rolePermissionRepository) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    public Set<String> resolvePermissions(UUID userId) {
        List<UUID> roleIds = userRoleRepository.findRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<String> permissions = rolePermissionRepository.findPermissionsByRoleIds(roleIds);
        return new HashSet<>(permissions);
    }
}
```

**Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=JpaEntitlementsServiceTest
```

Expected: PASS

**Step 5: Implement JpaWhoManagementService**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaWhoManagementService.java`:

```java
package org.jwcarman.who.jpa.service;

import org.jwcarman.who.core.service.WhoManagementService;
import org.jwcarman.who.jpa.entity.*;
import org.jwcarman.who.jpa.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class JpaWhoManagementService implements WhoManagementService {

    private final UserRepository userRepository;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;

    public JpaWhoManagementService(
            UserRepository userRepository,
            ExternalIdentityRepository externalIdentityRepository,
            RoleRepository roleRepository,
            RolePermissionRepository rolePermissionRepository,
            UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Override
    public void linkExternalIdentity(UUID userId, String issuer, String subject) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        if (externalIdentityRepository.findByIssuerAndSubject(issuer, subject).isPresent()) {
            throw new IllegalStateException(
                "Identity already linked: issuer=" + issuer + ", subject=" + subject);
        }

        ExternalIdentityEntity identity = new ExternalIdentityEntity();
        identity.setUserId(userId);
        identity.setIssuer(issuer);
        identity.setSubject(subject);
        externalIdentityRepository.save(identity);
    }

    @Override
    public void unlinkExternalIdentity(UUID userId, UUID externalIdentityId) {
        ExternalIdentityEntity identity = externalIdentityRepository.findById(externalIdentityId)
            .orElseThrow(() -> new IllegalArgumentException(
                "External identity not found: " + externalIdentityId));

        if (!identity.getUserId().equals(userId)) {
            throw new IllegalArgumentException(
                "External identity does not belong to user: " + userId);
        }

        long count = externalIdentityRepository.countByUserId(userId);
        if (count <= 1) {
            throw new IllegalStateException(
                "Cannot unlink last external identity for user: " + userId);
        }

        externalIdentityRepository.delete(identity);
    }

    @Override
    public UUID createRole(String roleName) {
        if (roleRepository.findByName(roleName).isPresent()) {
            throw new IllegalArgumentException("Role already exists: " + roleName);
        }

        RoleEntity role = new RoleEntity();
        role.setName(roleName);
        roleRepository.save(role);
        return role.getId();
    }

    @Override
    public void deleteRole(UUID roleId) {
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }
        rolePermissionRepository.deleteByRoleId(roleId);
        roleRepository.deleteById(roleId);
    }

    @Override
    public void assignRoleToUser(UUID userId, UUID roleId) {
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleRepository.save(userRole);
    }

    @Override
    public void removeRoleFromUser(UUID userId, UUID roleId) {
        userRoleRepository.deleteByUserIdAndRoleId(userId, roleId);
    }

    @Override
    public void addPermissionToRole(UUID roleId, String permission) {
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        RolePermissionEntity rolePermission = new RolePermissionEntity();
        rolePermission.setRoleId(roleId);
        rolePermission.setPermission(permission);
        rolePermissionRepository.save(rolePermission);
    }

    @Override
    public void removePermissionFromRole(UUID roleId, String permission) {
        RolePermissionEntity.RolePermissionId id =
            new RolePermissionEntity.RolePermissionId(roleId, permission);
        rolePermissionRepository.deleteById(id);
    }
}
```

**Step 6: Commit JPA service implementations**

```bash
cd ..
git add who-jpa/src/main/java/org/jwcarman/who/jpa/service/
git add who-jpa/src/test/java/org/jwcarman/who/jpa/service/
git commit -m "feat(jpa): implement entitlements and management services"
```

---

## Task 8: who-core - Preferences Service with JSON Merge

**Files:**
- Create: `who-core/pom.xml` (update to add Jackson)
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/impl/JsonPreferencesMerger.java`
- Create: `who-core/src/test/java/org/jwcarman/who/core/service/impl/JsonPreferencesMergerTest.java`

**Step 1: Update who-core POM to add Jackson**

Edit `who-core/pom.xml` to add Jackson dependency:

```xml
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**Step 2: Write failing test for JsonPreferencesMerger**

Create `who-core/src/test/java/org/jwcarman/who/core/service/impl/JsonPreferencesMergerTest.java`:

```java
package org.jwcarman.who.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPreferencesMergerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonPreferencesMerger merger = new JsonPreferencesMerger(objectMapper);

    @Test
    void shouldMergeSimplePreferences() {
        TestPrefs layer1 = new TestPrefs("en", "UTC", null);
        TestPrefs layer2 = new TestPrefs(null, "America/New_York", "dark");

        TestPrefs result = merger.merge(TestPrefs.class, layer1, layer2);

        assertThat(result.locale()).isEqualTo("en");
        assertThat(result.timezone()).isEqualTo("America/New_York");
        assertThat(result.theme()).isEqualTo("dark");
    }

    @Test
    void shouldNotOverrideWithNulls() {
        TestPrefs layer1 = new TestPrefs("en", "UTC", "light");
        TestPrefs layer2 = new TestPrefs(null, null, null);

        TestPrefs result = merger.merge(TestPrefs.class, layer1, layer2);

        assertThat(result.locale()).isEqualTo("en");
        assertThat(result.timezone()).isEqualTo("UTC");
        assertThat(result.theme()).isEqualTo("light");
    }

    @Test
    void shouldMergeMultipleLayers() {
        TestPrefs layer1 = new TestPrefs("en", "UTC", "light");
        TestPrefs layer2 = new TestPrefs(null, "America/New_York", null);
        TestPrefs layer3 = new TestPrefs(null, null, "dark");

        TestPrefs result = merger.merge(TestPrefs.class, layer1, layer2, layer3);

        assertThat(result.locale()).isEqualTo("en");
        assertThat(result.timezone()).isEqualTo("America/New_York");
        assertThat(result.theme()).isEqualTo("dark");
    }

    record TestPrefs(String locale, String timezone, String theme) {}
}
```

**Step 3: Run test to verify it fails**

```bash
cd who-core
mvn test -Dtest=JsonPreferencesMergerTest
```

Expected: FAIL with compilation error

**Step 4: Implement JsonPreferencesMerger**

Create `who-core/src/main/java/org/jwcarman/who/core/service/impl/JsonPreferencesMerger.java`:

```java
package org.jwcarman.who.core.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Utility for merging preferences using deep JSON merge.
 */
public class JsonPreferencesMerger {

    private final ObjectMapper objectMapper;

    public JsonPreferencesMerger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Merge multiple preference layers. Later layers override earlier layers.
     * Null values are skipped (do not override).
     *
     * @param type preference class type
     * @param layers preference layers to merge
     * @return merged preferences
     */
    @SafeVarargs
    public final <T> T merge(Class<T> type, T... layers) {
        if (layers.length == 0) {
            throw new IllegalArgumentException("At least one layer required");
        }

        ObjectNode result = objectMapper.createObjectNode();

        for (T layer : layers) {
            if (layer != null) {
                JsonNode layerNode = objectMapper.valueToTree(layer);
                deepMerge(result, layerNode);
            }
        }

        return objectMapper.convertValue(result, type);
    }

    private void deepMerge(ObjectNode target, JsonNode source) {
        if (!source.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            if (value.isNull()) {
                // Skip null values - don't override
                continue;
            }

            if (value.isObject() && target.has(key) && target.get(key).isObject()) {
                // Deep merge nested objects
                deepMerge((ObjectNode) target.get(key), value);
            } else {
                // Replace value (including arrays)
                target.set(key, value);
            }
        }
    }
}
```

**Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=JsonPreferencesMergerTest
```

Expected: PASS

**Step 6: Commit JSON preferences merger**

```bash
cd ..
git add who-core/
git commit -m "feat(core): add JSON preferences merger with deep merge support"
```

---

## Task 9: who-jpa - Preferences Service Implementation

**Files:**
- Create: `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaPreferencesService.java`
- Create: `who-jpa/src/test/java/org/jwcarman/who/jpa/service/JpaPreferencesServiceTest.java`

**Step 1: Write failing test for JpaPreferencesService**

Create `who-jpa/src/test/java/org/jwcarman/who/jpa/service/JpaPreferencesServiceTest.java`:

```java
package org.jwcarman.who.jpa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.service.impl.JsonPreferencesMerger;
import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.jwcarman.who.jpa.repository.UserPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository repository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private JsonPreferencesMerger merger = new JsonPreferencesMerger(new ObjectMapper());

    @InjectMocks
    private JpaPreferencesService service;

    @Test
    void shouldReturnDefaultsWhenNoPreferencesStored() {
        UUID userId = UUID.randomUUID();
        String namespace = "ui";

        when(repository.findByUserIdAndNamespace(userId, namespace))
            .thenReturn(Optional.empty());

        TestPrefs prefs = service.getPreferences(userId, namespace, TestPrefs.class);

        assertThat(prefs.theme()).isNull();
        assertThat(prefs.locale()).isNull();
    }

    @Test
    void shouldReturnStoredPreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        String namespace = "ui";
        String json = "{\"theme\":\"dark\",\"locale\":\"en\"}";

        UserPreferencesEntity entity = new UserPreferencesEntity();
        entity.setUserId(userId);
        entity.setNamespace(namespace);
        entity.setPrefsJson(json);

        when(repository.findByUserIdAndNamespace(userId, namespace))
            .thenReturn(Optional.of(entity));

        TestPrefs prefs = service.getPreferences(userId, namespace, TestPrefs.class);

        assertThat(prefs.theme()).isEqualTo("dark");
        assertThat(prefs.locale()).isEqualTo("en");
    }

    @Test
    void shouldSavePreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        String namespace = "ui";
        TestPrefs prefs = new TestPrefs("dark", "en");

        service.setPreferences(userId, namespace, prefs);

        ArgumentCaptor<UserPreferencesEntity> captor =
            ArgumentCaptor.forClass(UserPreferencesEntity.class);
        verify(repository).save(captor.capture());

        UserPreferencesEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getNamespace()).isEqualTo(namespace);
        assertThat(saved.getPrefsJson()).contains("dark", "en");
    }

    record TestPrefs(String theme, String locale) {}
}
```

**Step 2: Run test to verify it fails**

```bash
cd who-jpa
mvn test -Dtest=JpaPreferencesServiceTest
```

Expected: FAIL with compilation error

**Step 3: Implement JpaPreferencesService**

Create `who-jpa/src/main/java/org/jwcarman/who/jpa/service/JpaPreferencesService.java`:

```java
package org.jwcarman.who.jpa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.service.PreferencesService;
import org.jwcarman.who.core.service.impl.JsonPreferencesMerger;
import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.jwcarman.who.jpa.repository.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class JpaPreferencesService implements PreferencesService {

    private final UserPreferencesRepository repository;
    private final ObjectMapper objectMapper;
    private final JsonPreferencesMerger merger;

    public JpaPreferencesService(
            UserPreferencesRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.merger = new JsonPreferencesMerger(objectMapper);
    }

    @Override
    public <T> T getPreferences(UUID userId, String namespace, Class<T> type) {
        return repository.findByUserIdAndNamespace(userId, namespace)
            .map(entity -> deserialize(entity.getPrefsJson(), type))
            .orElseGet(() -> {
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create default instance of " + type, e);
                }
            });
    }

    @Override
    public <T> void setPreferences(UUID userId, String namespace, T preferences) {
        String json = serialize(preferences);

        UserPreferencesEntity entity = repository.findByUserIdAndNamespace(userId, namespace)
            .orElseGet(() -> {
                UserPreferencesEntity newEntity = new UserPreferencesEntity();
                newEntity.setUserId(userId);
                newEntity.setNamespace(namespace);
                return newEntity;
            });

        entity.setPrefsJson(json);
        repository.save(entity);
    }

    @Override
    @SafeVarargs
    public final <T> T mergePreferences(Class<T> type, T... layers) {
        return merger.merge(type, layers);
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize preferences", e);
        }
    }

    private String serialize(Object preferences) {
        try {
            return objectMapper.writeValueAsString(preferences);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize preferences", e);
        }
    }
}
```

**Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=JpaPreferencesServiceTest
```

Expected: PASS

**Step 5: Commit preferences service**

```bash
cd ..
git add who-jpa/
git commit -m "feat(jpa): implement preferences service with JSON storage"
```

---

## Task 10: who-security Module - Spring Security Integration

**Files:**
- Create: `who-security/pom.xml`
- Create: `who-security/src/main/java/org/jwcarman/who/security/WhoAuthenticationConverter.java`
- Create: `who-security/src/main/java/org/jwcarman/who/security/IdentityResolver.java`
- Create: `who-security/src/test/java/org/jwcarman/who/security/WhoAuthenticationConverterTest.java`

**Step 1: Create who-security POM**

Create `who-security/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.jwcarman.who</groupId>
        <artifactId>who-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>who-security</artifactId>
    <name>Who - Security</name>
    <description>Spring Security integration</description>

    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 2: Create IdentityResolver interface**

Create `who-security/src/main/java/org/jwcarman/who/security/IdentityResolver.java`:

```java
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;

import java.util.UUID;

/**
 * Resolves external identities to internal user IDs.
 */
public interface IdentityResolver {

    /**
     * Resolve external identity to internal user ID.
     *
     * @param identityKey external identity key
     * @return internal user ID, or null if not found/denied
     */
    UUID resolveUserId(ExternalIdentityKey identityKey);
}
```

**Step 3: Write failing test for WhoAuthenticationConverter**

Create `who-security/src/test/java/org/jwcarman/who/security/WhoAuthenticationConverterTest.java`:

```java
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.EntitlementsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhoAuthenticationConverterTest {

    @Mock
    private IdentityResolver identityResolver;

    @Mock
    private EntitlementsService entitlementsService;

    @InjectMocks
    private WhoAuthenticationConverter converter;

    @Test
    void shouldConvertJwtToAuthentication() {
        String issuer = "https://auth.example.com";
        String subject = "user123";
        UUID userId = UUID.randomUUID();

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("iss", issuer)
            .claim("sub", subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(userId);
        when(entitlementsService.resolvePermissions(userId))
            .thenReturn(Set.of("billing.invoice.read", "billing.invoice.write"));

        Authentication auth = converter.convert(jwt);

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(WhoPrincipal.class);

        WhoPrincipal principal = (WhoPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.issuer()).isEqualTo(issuer);
        assertThat(principal.subject()).isEqualTo(subject);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");

        assertThat(auth.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnNullWhenIdentityNotResolved() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "unknown")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(null);

        Authentication auth = converter.convert(jwt);

        assertThat(auth).isNull();
    }
}
```

**Step 4: Run test to verify it fails**

```bash
cd who-security
mvn test -Dtest=WhoAuthenticationConverterTest
```

Expected: FAIL with compilation error

**Step 5: Implement WhoAuthenticationConverter**

Create `who-security/src/main/java/org/jwcarman/who/security/WhoAuthenticationConverter.java`:

```java
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.EntitlementsService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Converts a JWT to a Spring Security Authentication with WhoPrincipal.
 */
public class WhoAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdentityResolver identityResolver;
    private final EntitlementsService entitlementsService;

    public WhoAuthenticationConverter(
            IdentityResolver identityResolver,
            EntitlementsService entitlementsService) {
        this.identityResolver = identityResolver;
        this.entitlementsService = entitlementsService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getClaimAsString("sub");

        ExternalIdentityKey identityKey = new ExternalIdentityKey(issuer, subject);
        UUID userId = identityResolver.resolveUserId(identityKey);

        if (userId == null) {
            // Identity not found or denied
            return null;
        }

        Set<String> permissions = entitlementsService.resolvePermissions(userId);
        WhoPrincipal principal = new WhoPrincipal(userId, issuer, subject, permissions);

        Set<GrantedAuthority> authorities = permissions.stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        return new JwtAuthenticationToken(jwt, authorities, principal);
    }
}
```

**Step 6: Run test to verify it passes**

```bash
mvn test -Dtest=WhoAuthenticationConverterTest
```

Expected: PASS

**Step 7: Commit Spring Security integration**

```bash
cd ..
git add who-security/
git commit -m "feat(security): add JWT authentication converter with WhoPrincipal"
```

---

## Task 11: who-security - Identity Resolver Implementation

**Files:**
- Create: `who-security/src/main/java/org/jwcarman/who/security/JpaIdentityResolver.java`
- Create: `who-security/src/test/java/org/jwcarman/who/security/JpaIdentityResolverTest.java`

**Step 1: Write failing test for JpaIdentityResolver**

Create `who-security/src/test/java/org/jwcarman/who/security/JpaIdentityResolverTest.java`:

```java
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaIdentityResolverTest {

    @Mock
    private ExternalIdentityRepository repository;

    @Mock
    private UserProvisioningPolicy provisioningPolicy;

    @InjectMocks
    private JpaIdentityResolver resolver;

    @Test
    void shouldResolveExistingIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "user123";
        UUID userId = UUID.randomUUID();

        ExternalIdentityEntity entity = new ExternalIdentityEntity();
        entity.setUserId(userId);

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.of(entity));

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(userId);
    }

    @Test
    void shouldDelegateToProvisioningPolicyForUnknownIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "newuser";
        UUID newUserId = UUID.randomUUID();

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(newUserId);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(newUserId);
    }

    @Test
    void shouldReturnNullWhenProvisioningPolicyDenies() {
        String issuer = "https://auth.example.com";
        String subject = "denied";

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(null);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isNull();
    }
}
```

**Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=JpaIdentityResolverTest
```

Expected: FAIL with compilation error

**Step 3: Update who-security POM to add who-jpa dependency**

Edit `who-security/pom.xml` to add:

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-jpa</artifactId>
    <version>${project.version}</version>
    <optional>true</optional>
</dependency>
```

**Step 4: Implement JpaIdentityResolver**

Create `who-security/src/main/java/org/jwcarman/who/security/JpaIdentityResolver.java`:

```java
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JPA-based identity resolver with provisioning policy support.
 */
@Component
public class JpaIdentityResolver implements IdentityResolver {

    private final ExternalIdentityRepository repository;
    private final UserProvisioningPolicy provisioningPolicy;

    public JpaIdentityResolver(
            ExternalIdentityRepository repository,
            UserProvisioningPolicy provisioningPolicy) {
        this.repository = repository;
        this.provisioningPolicy = provisioningPolicy;
    }

    @Override
    public UUID resolveUserId(ExternalIdentityKey identityKey) {
        return repository.findByIssuerAndSubject(identityKey.issuer(), identityKey.subject())
            .map(entity -> entity.getUserId())
            .orElseGet(() -> provisioningPolicy.handleUnknownIdentity(identityKey));
    }
}
```

**Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=JpaIdentityResolverTest
```

Expected: PASS

**Step 6: Commit JPA identity resolver**

```bash
cd ..
git add who-security/
git commit -m "feat(security): add JPA identity resolver with provisioning policy"
```

---

## Task 12: who-security - Default Provisioning Policies

**Files:**
- Create: `who-security/src/main/java/org/jwcarman/who/security/policy/DenyUnknownIdentityPolicy.java`
- Create: `who-security/src/main/java/org/jwcarman/who/security/policy/AutoProvisionIdentityPolicy.java`

**Step 1: Create DenyUnknownIdentityPolicy**

Create `who-security/src/main/java/org/jwcarman/who/security/policy/DenyUnknownIdentityPolicy.java`:

```java
package org.jwcarman.who.security.policy;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Provisioning policy that denies access to unknown identities.
 */
public class DenyUnknownIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(DenyUnknownIdentityPolicy.class);

    @Override
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.warn("Denied unknown identity: issuer={}, subject={}",
            identityKey.issuer(), identityKey.subject());
        return null;
    }
}
```

**Step 2: Create AutoProvisionIdentityPolicy**

Create `who-security/src/main/java/org/jwcarman/who/security/policy/AutoProvisionIdentityPolicy.java`:

```java
package org.jwcarman.who.security.policy;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.jwcarman.who.jpa.entity.UserEntity;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.jwcarman.who.jpa.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Provisioning policy that auto-creates new users for unknown identities.
 */
public class AutoProvisionIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(AutoProvisionIdentityPolicy.class);

    private final UserRepository userRepository;
    private final ExternalIdentityRepository identityRepository;

    public AutoProvisionIdentityPolicy(
            UserRepository userRepository,
            ExternalIdentityRepository identityRepository) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
    }

    @Override
    @Transactional
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.info("Auto-provisioning new user for identity: issuer={}, subject={}",
            identityKey.issuer(), identityKey.subject());

        // Create new user
        UserEntity user = new UserEntity();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        // Link external identity
        ExternalIdentityEntity identity = new ExternalIdentityEntity();
        identity.setUserId(user.getId());
        identity.setIssuer(identityKey.issuer());
        identity.setSubject(identityKey.subject());
        identityRepository.save(identity);

        log.info("Auto-provisioned user: userId={}", user.getId());
        return user.getId();
    }
}
```

**Step 3: Commit provisioning policies**

```bash
git add who-security/src/main/java/org/jwcarman/who/security/policy/
git commit -m "feat(security): add deny and auto-provision identity policies"
```

---

## Task 13: who-web Module - REST Controllers

**Files:**
- Create: `who-web/pom.xml`
- Create: `who-web/src/main/java/org/jwcarman/who/web/WhoManagementController.java`
- Create: `who-web/src/main/java/org/jwcarman/who/web/PreferencesController.java`

**Step 1: Create who-web POM**

Create `who-web/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.jwcarman.who</groupId>
        <artifactId>who-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>who-web</artifactId>
    <name>Who - Web</name>
    <description>REST controllers</description>

    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**Step 2: Create WhoManagementController**

Create `who-web/src/main/java/org/jwcarman/who/web/WhoManagementController.java`:

```java
package org.jwcarman.who.web;

import org.jwcarman.who.core.service.WhoManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for managing roles and identities.
 */
@RestController
@RequestMapping("/api/who/management")
public class WhoManagementController {

    private final WhoManagementService managementService;

    public WhoManagementController(WhoManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('who.role.create')")
    public RoleResponse createRole(@RequestBody CreateRoleRequest request) {
        UUID roleId = managementService.createRole(request.roleName());
        return new RoleResponse(roleId, request.roleName());
    }

    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.delete')")
    public void deleteRole(@PathVariable UUID roleId) {
        managementService.deleteRole(roleId);
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.assign')")
    public void assignRoleToUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        managementService.assignRoleToUser(userId, roleId);
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.remove')")
    public void removeRoleFromUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        managementService.removeRoleFromUser(userId, roleId);
    }

    @PostMapping("/roles/{roleId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.add')")
    public void addPermissionToRole(@PathVariable UUID roleId, @RequestBody AddPermissionRequest request) {
        managementService.addPermissionToRole(roleId, request.permission());
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permission}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.remove')")
    public void removePermissionFromRole(@PathVariable UUID roleId, @PathVariable String permission) {
        managementService.removePermissionFromRole(roleId, permission);
    }

    record CreateRoleRequest(String roleName) {}
    record RoleResponse(UUID roleId, String roleName) {}
    record AddPermissionRequest(String permission) {}
}
```

**Step 3: Create PreferencesController**

Create `who-web/src/main/java/org/jwcarman/who/web/PreferencesController.java`:

```java
package org.jwcarman.who.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.PreferencesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing user preferences.
 */
@RestController
@RequestMapping("/api/who/preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{namespace}")
    public JsonNode getPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace) {
        return preferencesService.getPreferences(principal.userId(), namespace, JsonNode.class);
    }

    @PutMapping("/{namespace}")
    public void setPreferences(
            @AuthenticationPrincipal WhoPrincipal principal,
            @PathVariable String namespace,
            @RequestBody JsonNode preferences) {
        preferencesService.setPreferences(principal.userId(), namespace, preferences);
    }
}
```

**Step 4: Commit web controllers**

```bash
git add who-web/
git commit -m "feat(web): add REST controllers for management and preferences"
```

---

## Task 14: who-starter - Spring Boot Auto-Configuration

**Files:**
- Create: `who-starter/pom.xml`
- Create: `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoAutoConfiguration.java`
- Create: `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoSecurityAutoConfiguration.java`
- Create: `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoProperties.java`
- Create: `who-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Step 1: Create who-starter POM**

Create `who-starter/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.jwcarman.who</groupId>
        <artifactId>who-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>who-starter</artifactId>
    <name>Who - Starter</name>
    <description>Spring Boot starter for Who library</description>

    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-jpa</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-security</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-web</artifactId>
            <version>${project.version}</version>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

**Step 2: Create WhoProperties**

Create `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoProperties.java`:

```java
package org.jwcarman.who.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Who library.
 */
@ConfigurationProperties(prefix = "who")
public class WhoProperties {

    private Provisioning provisioning = new Provisioning();

    public Provisioning getProvisioning() {
        return provisioning;
    }

    public void setProvisioning(Provisioning provisioning) {
        this.provisioning = provisioning;
    }

    public static class Provisioning {
        /**
         * Auto-provision new users for unknown identities.
         * If false, unknown identities are denied access.
         */
        private boolean autoProvision = false;

        public boolean isAutoProvision() {
            return autoProvision;
        }

        public void setAutoProvision(boolean autoProvision) {
            this.autoProvision = autoProvision;
        }
    }
}
```

**Step 3: Create WhoAutoConfiguration**

Create `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoAutoConfiguration.java`:

```java
package org.jwcarman.who.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.jwcarman.who.jpa.repository.UserRepository;
import org.jwcarman.who.security.policy.AutoProvisionIdentityPolicy;
import org.jwcarman.who.security.policy.DenyUnknownIdentityPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for Who library.
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
@ComponentScan(basePackages = {
    "org.jwcarman.who.jpa",
    "org.jwcarman.who.security",
    "org.jwcarman.who.web"
})
public class WhoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "true")
    public UserProvisioningPolicy autoProvisionPolicy(
            UserRepository userRepository,
            ExternalIdentityRepository identityRepository) {
        return new AutoProvisionIdentityPolicy(userRepository, identityRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "false", matchIfMissing = true)
    public UserProvisioningPolicy denyUnknownPolicy() {
        return new DenyUnknownIdentityPolicy();
    }
}
```

**Step 4: Create WhoSecurityAutoConfiguration**

Create `who-starter/src/main/java/org/jwcarman/who/autoconfig/WhoSecurityAutoConfiguration.java`:

```java
package org.jwcarman.who.autoconfig;

import org.jwcarman.who.core.service.EntitlementsService;
import org.jwcarman.who.security.IdentityResolver;
import org.jwcarman.who.security.WhoAuthenticationConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Auto-configuration for Who Spring Security integration.
 */
@AutoConfiguration
@EnableMethodSecurity
public class WhoSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            IdentityResolver identityResolver,
            EntitlementsService entitlementsService) {
        WhoAuthenticationConverter converter =
            new WhoAuthenticationConverter(identityResolver, entitlementsService);

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt ->
            converter.convert(jwt).getAuthorities());
        jwtConverter.setPrincipalClaimName("sub");

        return converter;
    }
}
```

**Step 5: Create Spring Boot auto-configuration imports file**

Create `who-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
org.jwcarman.who.autoconfig.WhoAutoConfiguration
org.jwcarman.who.autoconfig.WhoSecurityAutoConfiguration
```

**Step 6: Commit starter module**

```bash
git add who-starter/
git commit -m "feat(starter): add Spring Boot auto-configuration"
```

---

## Task 15: Documentation and Build Verification

**Files:**
- Update: `README.md`
- Create: `docs/USAGE.md`

**Step 1: Update README with usage instructions**

Update `README.md`:

```markdown
# Who - Spring Boot Identity & Entitlements Framework

A reusable Spring Boot library for OAuth2/JWT authentication, internal identity mapping, RBAC authorization, and user preferences.

## Features

- **JWT Authentication**: OAuth2 resource server with multi-issuer support
- **Internal Identity Mapping**: Map external `(issuer, subject)` to stable internal user IDs
- **RBAC Authorization**: Role-based permissions as Spring Security authorities
- **User Preferences**: Namespaced JSON preferences with defaults and deep merge
- **Identity Linking**: Link multiple external identities to one internal user
- **Auto-Provisioning**: Configurable policy for handling unknown identities

## Modules

- `who-core` - Core domain types and interfaces
- `who-jpa` - JPA entities and repositories (Liquibase migrations)
- `who-security` - Spring Security integration
- `who-web` - REST controllers (optional)
- `who-starter` - Spring Boot auto-configuration

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Application

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-provider.com

who:
  provisioning:
    auto-provision: false  # Set to true to auto-create users
```

### 3. Use in Controllers

```java
@RestController
public class BillingController {

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing.invoice.read')")
    public List<Invoice> getInvoices(@AuthenticationPrincipal WhoPrincipal principal) {
        UUID userId = principal.userId();
        // ... use userId to fetch user-specific data
    }
}
```

## Build

```bash
mvn clean install
```

## Documentation

See [docs/USAGE.md](docs/USAGE.md) for detailed usage instructions.

## License

TBD
```

**Step 2: Create usage documentation**

Create `docs/USAGE.md`:

```markdown
# Who Library - Usage Guide

## Configuration

### Database

Configure your database connection:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: dbuser
    password: dbpass
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml
```

### OAuth2 Resource Server

Configure trusted JWT issuers:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com
```

### Provisioning Policy

```yaml
who:
  provisioning:
    auto-provision: true  # Auto-create users for unknown identities
```

## Managing Roles and Permissions

### Creating Roles

```bash
POST /api/who/management/roles
{
  "roleName": "billing-admin"
}
```

### Adding Permissions to Roles

```bash
POST /api/who/management/roles/{roleId}/permissions
{
  "permission": "billing.invoice.read"
}
```

### Assigning Roles to Users

```bash
POST /api/who/management/users/{userId}/roles/{roleId}
```

## Using Preferences

### Get Preferences

```bash
GET /api/who/preferences/ui
```

Returns user's preferences for the "ui" namespace.

### Set Preferences

```bash
PUT /api/who/preferences/ui
{
  "theme": "dark",
  "locale": "en"
}
```

## Authorization

Use Spring Security's `@PreAuthorize` annotation:

```java
@PreAuthorize("hasAuthority('billing.invoice.write')")
public void createInvoice() { ... }
```

## Accessing the Principal

```java
@GetMapping("/profile")
public UserProfile getProfile(@AuthenticationPrincipal WhoPrincipal principal) {
    UUID userId = principal.userId();
    Set<String> permissions = principal.permissions();
    // ...
}
```
```

**Step 3: Build entire project**

```bash
mvn clean install
```

Expected: SUCCESS (all modules compile and tests pass)

**Step 4: Commit documentation**

```bash
git add README.md docs/USAGE.md
git commit -m "docs: add README and usage documentation"
```

---

## Task 16: Final Review and Tag

**Step 1: Run full test suite**

```bash
mvn clean test
```

Expected: All tests pass

**Step 2: Verify Liquibase migrations**

Check that all Liquibase changesets are valid:

```bash
cd who-jpa
mvn liquibase:validate
```

Expected: Validation successful (or skip if liquibase plugin not configured)

**Step 3: Create initial git tag**

```bash
git tag -a v0.1.0-SNAPSHOT -m "Initial implementation of Who library"
```

**Step 4: Summary**

The Who library is now complete with:

- ✅ Multi-module Maven project structure
- ✅ Core domain types (WhoPrincipal, ExternalIdentityKey, UserStatus)
- ✅ JPA entities with Liquibase migrations
- ✅ Spring Data JPA repositories
- ✅ Service implementations (Entitlements, Management, Preferences)
- ✅ Spring Security integration with JWT authentication
- ✅ Identity resolver with provisioning policies
- ✅ JSON preferences with deep merge support
- ✅ REST controllers for management and preferences
- ✅ Spring Boot auto-configuration
- ✅ Documentation

---

## Next Steps (Post-Implementation)

1. **Integration Testing**: Create a sample Spring Boot application that uses `who-starter`
2. **Additional Tests**: Add integration tests with Testcontainers for database scenarios
3. **Performance**: Add caching for entitlements resolution
4. **Audit Events**: Implement audit event publishing for sensitive operations
5. **Metrics**: Add Micrometer metrics for authentication and authorization
6. **Multi-Issuer Support**: Test with multiple JWT issuers
7. **Publishing**: Configure Maven for publishing to repository (Maven Central or private)

