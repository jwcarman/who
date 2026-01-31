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
