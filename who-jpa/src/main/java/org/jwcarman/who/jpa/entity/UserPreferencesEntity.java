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
