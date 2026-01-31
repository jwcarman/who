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
